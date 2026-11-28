package com.domenota.medialoader.data.catalog.visibility

import com.domenota.medialoader.data.tasks.*
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.domenota.medialoader.MainActivity
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.data.MediaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Notification words must not infer a verified physical result from the
 * absence of a journal. This is a pure, privacy-preserving projection of the
 * same state displayed by the foreground coordinator. */
internal fun visibilityForegroundMessage(state: VisibilityExecutionSnapshot): String = when(state.phase) {
    VisibilityExecutionPhase.WAITING_FOR_ANDROID -> "Нужно подтвердить действие в приложении"
    VisibilityExecutionPhase.PAUSE_REQUESTED -> "Сохраняем безопасную контрольную точку"
    VisibilityExecutionPhase.PAUSED -> "Перенос приостановлен"
    VisibilityExecutionPhase.RECOVERABLE_PENDING -> "Перенос ожидает проверки"
    VisibilityExecutionPhase.FAILED -> if(state.hasPendingJournal)
        "Перенос остановлен; требуется проверка журнала"
        else "Операция остановлена; подробности в журнале"
    VisibilityExecutionPhase.VERIFYING -> "Проверка результатов переноса"
    VisibilityExecutionPhase.COMPLETED -> "Файлы перенесены; медиатека проверяется"
    VisibilityExecutionPhase.RUNNING -> "Подтверждено: ${state.confirmed} из ${state.total}"
    else -> "Подготовка переноса файлов"
}

/** A separate foreground owner, not part of ActiveDownloadService. The
 * in-memory command nonce must come from an explicit visible-Activity action.
 * START_NOT_STICKY: after process death, pending data is preserved in the
 * durable batch/move journals, but never automatically deleted or resumed. */
class FolderVisibilityService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var operation: Job? = null
    private var observer: Job? = null
    private var reporter:TaskReporter?=null
    private var taskTerminal=false
    private var reportedStatus:TaskStatus?=null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"Безопасный перенос файлов",NotificationManager.IMPORTANCE_LOW))
    }

    @RequiresApi(30)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Establish foreground promptly, before repository/Room initialization,
        // any file preflight or IPC. FGS creation may be timed by Android.
        try {
            val initial = buildNotification(VisibilityExecutionSnapshot(VisibilityExecutionPhase.PREPARING))
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIFICATION_ID, initial,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION_ID,initial)
        } catch (e: Exception) {
            AppLog.w("FolderVisibility","FOREGROUND_CREATE_FAILED type=${e.javaClass.simpleName}")
            val failedId = if (intent?.action == ACTION_RUN_REQUEST)
                intent.getLongExtra(EXTRA_REQUEST_ID,-1L) else -1L
            runCatching {
                MediaRepository.get(applicationContext).folderVisibilityWork
                    .rejectUnclaimedForegroundStart(failedId)
            }
            // A failed foreground promotion must not leave an unpromoted
            // Service alive if a newer startId arrived in the meantime.
            stopSelf()
            return START_NOT_STICKY
        }

        val repository = MediaRepository.get(applicationContext)
        val coordinator = repository.folderVisibilityWork
        val id = if (intent?.action == ACTION_RUN_REQUEST)
            intent.getLongExtra(EXTRA_REQUEST_ID,-1L) else -1L
        // A second Intent can arrive after the old coordinator reservation
        // has been released but before Android destroys this Service. Never
        // claim that new command while the old Job still owns this Service:
        // it would either crash at check(operation == null), or an old
        // stopSelf() could destroy the new worker. Reject fail-closed and
        // preserve all pending durable journals for explicit retry.
        if (operation != null) {
            coordinator.rejectUnclaimedForegroundStart(id)
            runCatching {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID,buildNotification(coordinator.state.value))
            }
            AppLog.w("FolderVisibility","FOREGROUND_REENTRANT_REJECT activeServiceJob=true")
            return START_NOT_STICKY
        }
        val request = coordinator.claimServiceRequest(id)
        if (request == null) {
            // A replayed/null/foreign start must never execute file mutation.
            stopSelf()
            return START_NOT_STICKY
        }
        reporter=TaskReporter.create(TaskOwner.FILES,TaskKind.FILE_OPERATION,"visibility:$id")
        observer = scope.launch {
            coordinator.state.collectLatest { snapshot ->
                reportNotificationState(snapshot)
                runCatching {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID,buildNotification(snapshot))
                }
            }
        }
        operation = scope.launch {
            try { coordinator.executeReserved(request) }
            // A duplicate/stale onStartCommand may advance the platform
            // startId. Do not leak a running notification after work completes.
            finally { reportNotificationState(coordinator.state.value);stopSelf() }
        }
        return START_NOT_STICKY
    }

    @RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ requires unconditional stopSelf() within seconds.
        // stopSelfResult(startId) may return false after a newer
        // onStartCommand and then Android kills this process with a fatal FGS
        // timeout. The journal is already durable; do not block this callback
        // on Room initialization or a best-effort cooperative pause.
        AppLog.w("FolderVisibility",
            "FOREGROUND_TIMEOUT type=dataSync forcedStop=true checkpointUnconfirmed=true journalRetained=true")
        stopSelf()
    }

    override fun onDestroy() {
        if(!taskTerminal && reportedStatus==TaskStatus.RUNNING)reporter?.report(TaskStatus.PARTIAL,details=TaskDetails(failure=TaskFailure.INTERRUPTED))
        operation?.cancel()
        observer?.cancel()
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent:Intent?): IBinder? = null

    private fun reportNotificationState(state:VisibilityExecutionSnapshot) {
        val status=when(state.phase){
            VisibilityExecutionPhase.COMPLETED->if(state.hasPendingJournal)TaskStatus.PARTIAL else TaskStatus.COMPLETE
            VisibilityExecutionPhase.FAILED->TaskStatus.FAILED
            VisibilityExecutionPhase.RECOVERABLE_PENDING->TaskStatus.PARTIAL
            VisibilityExecutionPhase.PAUSED->TaskStatus.PAUSED
            VisibilityExecutionPhase.WAITING_FOR_ANDROID->TaskStatus.WAITING
            else->TaskStatus.RUNNING
        }
        taskTerminal=status.terminal
        reportedStatus=status
        reporter?.report(status,files=countProgress(state.confirmed,state.total),
            wait=if(status==TaskStatus.WAITING)TaskWait.USER_ACTION else null,
            errors=if(status==TaskStatus.FAILED)1 else 0,
            stage=if(state.phase==VisibilityExecutionPhase.VERIFYING)TaskStage.SAVING else TaskStage.PROCESSING)
    }

    private fun buildNotification(state: VisibilityExecutionSnapshot): Notification {
        val open = TaskNotifications.open(this,reporter?.taskId)
        val text=visibilityForegroundMessage(state)
        val builder = TaskNotifications.decorate(NotificationCompat.Builder(this,CHANNEL),TaskKind.FILE_OPERATION,
            "Операции с файлами",text)
            .setContentIntent(open)
            .setOngoing(state.active)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if(state.total > 0) builder.setProgress(state.total,state.confirmed,false)
        else if(state.active) builder.setProgress(0,0,true)
        return builder.build()
    }

    companion object {
        const val ACTION_RUN_REQUEST = "com.domenota.medialoader.visibility.RUN_EXPLICIT"
        const val EXTRA_REQUEST_ID = "visibility_request_nonce"
        private const val CHANNEL = "folder_visibility_transfer_v1"
        private const val NOTIFICATION_ID = 8396
    }
}

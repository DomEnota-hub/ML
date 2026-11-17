package com.domenota.medialoader.data.catalog.visibility

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

/** A separate foreground owner, not part of ActiveDownloadService. The
 * in-memory command nonce must come from an explicit visible-Activity action.
 * START_NOT_STICKY: after process death, pending data is preserved in the
 * durable batch/move journals, but never automatically deleted or resumed. */
class FolderVisibilityService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var operation: Job? = null
    private var observer: Job? = null

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
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        val repository = MediaRepository.get(applicationContext)
        val coordinator = repository.folderVisibilityWork
        val id = if (intent?.action == ACTION_RUN_REQUEST)
            intent.getLongExtra(EXTRA_REQUEST_ID,-1L) else -1L
        val request = coordinator.claimServiceRequest(id)
        if (request == null) {
            // A replayed/null/foreign start must not interrupt valid ongoing work.
            if (operation == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        check(operation == null) { "Нельзя запустить второй перенос" }
        observer = scope.launch {
            coordinator.state.collectLatest { snapshot ->
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
            finally { stopSelf() }
        }
        return START_NOT_STICKY
    }

    @RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLog.w("FolderVisibility","FOREGROUND_TIMEOUT type=dataSync; journal retained")
        MediaRepository.get(applicationContext).folderVisibilityWork.pauseAtNextCheckpoint()
        // Android mandates stopping shortly after timeout. An interrupted stage
        // remains durable/replayable; the service never claims transfer success.
        stopSelfResult(startId)
    }

    override fun onDestroy() {
        operation?.cancel()
        observer?.cancel()
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent:Intent?): IBinder? = null

    private fun buildNotification(state: VisibilityExecutionSnapshot): Notification {
        val open = PendingIntent.getActivity(this, 201,
            Intent(this,MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text=when(state.phase) {
            VisibilityExecutionPhase.WAITING_FOR_ANDROID -> "Нужно подтвердить действие в приложении"
            VisibilityExecutionPhase.PAUSE_REQUESTED -> "Сохраняем безопасную контрольную точку"
            VisibilityExecutionPhase.PAUSED -> "Перенос приостановлен"
            VisibilityExecutionPhase.RECOVERABLE_PENDING -> "Перенос ожидает проверки"
            VisibilityExecutionPhase.FAILED -> "Перенос остановлен, данные сохранены"
            VisibilityExecutionPhase.VERIFYING -> "Проверка результатов переноса"
            VisibilityExecutionPhase.COMPLETED -> "Перенос завершён"
            VisibilityExecutionPhase.RUNNING -> "Подтверждено: ${state.confirmed} из ${state.total}"
            else -> "Подготовка переноса файлов"
        }
        val builder = NotificationCompat.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("ML • Перенос файлов")
            .setContentText(text)
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

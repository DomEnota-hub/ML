package com.domenota.medialoader.data.library

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.domenota.medialoader.MainActivity
import com.domenota.medialoader.core.database.DownloadEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface DuplicateSearchState {
    data object Idle : DuplicateSearchState
    data class Running(val done: Int = 0, val total: Int = 0) : DuplicateSearchState
    data class Complete(val session: DuplicateSession) : DuplicateSearchState
    data class Failed(val message: String) : DuplicateSearchState
}

/** Started only by explicit user action in the foreground. The Activity never owns the scan. */
class DuplicateSearchService : Service() {
    companion object {
        const val OPEN = "com.domenota.medialoader.OPEN_DUPLICATES"
        private const val CANCEL = "com.domenota.medialoader.CANCEL_DUPLICATES"
        private const val CHANNEL = "duplicate-search"
        private const val ID = 18102
        private data class Request(val rows: List<DownloadEntity>, val linked: Set<String>, val current: String?)
        private var pending: Request? = null
        private val mutable = MutableStateFlow<DuplicateSearchState>(DuplicateSearchState.Idle)
        internal val state = mutable.asStateFlow()
        private val openMutable = MutableStateFlow(false)
        internal val openRequested = openMutable.asStateFlow()
        internal fun requestOpen() { openMutable.value = true }
        internal fun consumeOpen() { openMutable.value = false }
        internal fun acknowledge() { if (mutable.value !is DuplicateSearchState.Running) mutable.value = DuplicateSearchState.Idle }
        internal fun begin(context: Context, rows: List<DownloadEntity>, linked: Set<String>, current: String?) {
            if (mutable.value is DuplicateSearchState.Running) return
            pending = Request(rows.toList(), linked.toSet(), current)
            mutable.value = DuplicateSearchState.Running()
            try { ContextCompat.startForegroundService(context, Intent(context,DuplicateSearchService::class.java)) }
            catch (e: Exception) { pending = null; mutable.value = DuplicateSearchState.Failed("Не удалось запустить фоновый поиск: ${e.message.orEmpty()}") }
        }
        internal fun cancel(context: Context) {
            pending = null
            mutable.value = DuplicateSearchState.Failed("Поиск отменён. Предыдущий результат сохранён.")
            context.stopService(Intent(context,DuplicateSearchService::class.java))
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var task: Job? = null
    private var lastNotice = 0L
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"Поиск дубликатов",NotificationManager.IMPORTANCE_LOW))
    }
    private fun notice(done: Int, total: Int, finished: String? = null): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this,ID,Intent(this,MainActivity::class.java).setAction(OPEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),flags)
        val cancel = PendingIntent.getService(this,ID,Intent(this,DuplicateSearchService::class.java).setAction(CANCEL),flags)
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle("Поиск дубликатов треков")
            .setContentText(finished ?: "Проверено $done из $total")
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(finished == null)
            .setAutoCancel(finished != null).apply {
                if (finished == null) {
                    setProgress(total,done,total == 0)
                    addAction(0,"Открыть поиск",open)
                    addAction(0,"Отменить",cancel)
                }
            }.build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) { task?.cancel(); cancel(this); stopSelf(); return START_NOT_STICKY }
        if (task?.isActive == true) return START_NOT_STICKY
        val request = pending.also { pending = null }
        if (request == null) { stopSelf(); return START_NOT_STICKY }
        val notification = notice(0,0)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID,notification)
        task = scope.launch {
            var completed = false
            try {
                val r = MusicDuplicateScanner.scan(applicationContext,request.rows,request.linked,request.current) { n,total ->
                    if (mutable.value is DuplicateSearchState.Running) mutable.value = DuplicateSearchState.Running(n,total)
                    val now = SystemClock.elapsedRealtime()
                    if (now-lastNotice >= 750) {
                        lastNotice = now
                        runCatching { getSystemService(NotificationManager::class.java).notify(ID,notice(n,total)) }
                    }
                }
                ensureActive()
                val session = DuplicateSession(r,emptySet(),r.groups.associate { it.key to MusicDuplicatePolicy.recommended(it) },0,0,System.currentTimeMillis())
                check(MusicDuplicateSessionStore.save(applicationContext,session)) { "Не удалось сохранить результат поиска" }
                ensureActive()
                mutable.value = DuplicateSearchState.Complete(session)
                completed = true
                stopForeground(STOP_FOREGROUND_REMOVE)
                runCatching { getSystemService(NotificationManager::class.java).notify(ID,notice(0,0,"Найдено групп: ${r.groups.size}")) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = DuplicateSearchState.Failed(e.message ?: "Ошибка проверки") }
            finally {
                if (!completed) stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        mutable.value = DuplicateSearchState.Failed("Android остановил фоновую проверку. Запустите поиск снова; проверенные файлы сохранены.")
        task?.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        scope.cancel()
        if (mutable.value is DuplicateSearchState.Running) mutable.value = DuplicateSearchState.Failed("Поиск прерван. Проверенные файлы сохранены.")
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

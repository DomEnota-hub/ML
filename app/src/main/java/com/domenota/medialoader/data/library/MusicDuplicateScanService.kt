package com.domenota.medialoader.data.library

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.domenota.medialoader.MainActivity
import com.domenota.medialoader.core.database.DownloadEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread admission, one service-owned scan. The screen only observes progress. */
internal object DuplicateBackgroundScan {
    const val OPEN_ACTION = "com.domenota.medialoader.OPEN_DUPLICATES"
    data class Request(val rows: List<DownloadEntity>, val linked: Set<String>, val current: String?)
    data class Status(val running: Boolean = false, val done: Int = 0, val total: Int = 0,
        val session: DuplicateSession? = null, val error: String? = null)
    private val mutable = MutableStateFlow(Status())
    val state = mutable.asStateFlow()
    private var pending: Request? = null
    fun start(context: Context, rows: List<DownloadEntity>, linked: Set<String>, current: String?) {
        if (mutable.value.running) return
        pending = Request(rows.toList(),linked.toSet(),current)
        mutable.value = Status(running = true,total = rows.size)
        try { ContextCompat.startForegroundService(context,Intent(context,MusicDuplicateScanService::class.java)) }
        catch (e: Exception) { pending = null; mutable.value = Status(error = "Не удалось запустить фоновый поиск") }
    }
    fun claim(): Request? = pending.also { pending = null }
    fun progress(done: Int,total: Int) { mutable.value = Status(true,done,total) }
    fun finish(session: DuplicateSession?,error: String? = null) { mutable.value = Status(session = session,error = error) }
    fun cancel(context: Context) {
        pending = null
        context.stopService(Intent(context,MusicDuplicateScanService::class.java))
        finish(null,"Поиск отменён")
    }
}

class MusicDuplicateScanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var terminal = false
    private var lastNotice = 0L
    private val manager get() = getSystemService(NotificationManager::class.java)
    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"Поиск дубликатов",NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            terminal = true; job?.cancel(); DuplicateBackgroundScan.finish(null,"Поиск отменён")
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        if (job != null) return START_NOT_STICKY
        val request = DuplicateBackgroundScan.claim()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID,notice(0,request?.rows?.size ?: 0),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID,notice(0,request?.rows?.size ?: 0))
        if (request == null) { terminal = true; DuplicateBackgroundScan.finish(null,"Поиск прерван. Запустите его снова."); stopSelf(); return START_NOT_STICKY }
        job = scope.launch {
            try {
                val result = MusicDuplicateScanner.scan(this@MusicDuplicateScanService,request.rows,request.linked,request.current) { done,total ->
                    DuplicateBackgroundScan.progress(done,total)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastNotice >= 1000 || done == total) {
                        lastNotice = now
                        runCatching { manager.notify(ID,notice(done,total)) }
                    }
                }
                ensureActive()
                val session = DuplicateSession(result,emptySet(),result.groups.associate { it.key to MusicDuplicatePolicy.recommended(it) },0,0,System.currentTimeMillis())
                check(MusicDuplicateSessionStore.save(this@MusicDuplicateScanService,session)) { "Не удалось сохранить поиск" }
                terminal = true; DuplicateBackgroundScan.finish(session)
                stopForeground(STOP_FOREGROUND_REMOVE)
                runCatching { manager.notify(DONE_ID,notificationBase().setContentTitle("Поиск дубликатов завершён")
                    .setContentText("Групп: ${result.groups.size} · файлов: ${result.groups.sumOf { it.tracks.size }}")
                    .setAutoCancel(true).build()) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { terminal = true; DuplicateBackgroundScan.finish(null,e.message ?: "Ошибка поиска") }
            finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        terminal = true; job?.cancel(); DuplicateBackgroundScan.finish(null,"Поиск приостановлен Android. Продолжите при следующем открытии.")
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        scope.cancel()
        if (!terminal && DuplicateBackgroundScan.state.value.running) DuplicateBackgroundScan.finish(null,"Поиск прерван. Проверенные файлы сохранены.")
        super.onDestroy()
    }
    private fun notificationBase(): NotificationCompat.Builder {
        val launch = Intent(this,MainActivity::class.java).setAction(DuplicateBackgroundScan.OPEN_ACTION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(PendingIntent.getActivity(this,ID,launch,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOnlyAlertOnce(true)
    }
    private fun notice(done: Int,total: Int): Notification {
        val cancel = PendingIntent.getService(this,ID,Intent(this,MusicDuplicateScanService::class.java).setAction(CANCEL),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return notificationBase().setContentTitle("Поиск дубликатов треков").setContentText("Проверено $done из $total")
            .setProgress(total,done,total == 0).setOngoing(true).addAction(0,"Отменить",cancel).build()
    }
    companion object {
        private const val CHANNEL = "duplicate_scan"
        private const val ID = 8293
        private const val DONE_ID = 8294
        private const val CANCEL = "cancel_duplicate_scan"
    }
}

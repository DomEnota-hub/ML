package com.domenota.medialoader.data.library

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.Manifest
import android.content.pm.PackageManager
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
    data class Request(val token: Long, val rows: List<DownloadEntity>, val linked: Set<String>, val current: String?)
    data class Status(val running: Boolean = false, val done: Int = 0, val total: Int = 0,
        val session: DuplicateSession? = null, val error: String? = null, val token: Long = 0, val committing: Boolean = false)
    private val mutable = MutableStateFlow(Status())
    val state = mutable.asStateFlow()
    private var pending: Request? = null
    private var nextToken = 0L
    private fun prefs(context: Context) = context.getSharedPreferences("duplicate_background_scan",Context.MODE_PRIVATE)
    fun interrupted(context: Context) = prefs(context).getBoolean("incomplete",false)
    private fun mark(context: Context, value: Boolean) { prefs(context).edit().putBoolean("incomplete",value).apply() }
    @Synchronized fun start(context: Context, rows: List<DownloadEntity>, linked: Set<String>, current: String?) {
        if (mutable.value.running) return
        val token = ++nextToken
        pending = Request(token,rows.toList(),linked.toSet(),current)
        mutable.value = Status(running = true,total = rows.size,token = token)
        mark(context,true)
        try { ContextCompat.startForegroundService(context,Intent(context,MusicDuplicateScanService::class.java)) }
        catch (e: Exception) { pending = null; mutable.value = Status(error = "Не удалось запустить фоновый поиск",token = token) }
    }
    @Synchronized fun invalidateForPrivacy() {
        pending = null
        mutable.value = Status(error = "Поиск сброшен после переноса в Личное",token = ++nextToken)
    }
    @Synchronized fun claim(): Request? = pending.also { pending = null }
    @Synchronized fun progress(token: Long, done: Int,total: Int) {
        if (mutable.value.token == token && mutable.value.running && !mutable.value.committing)
            mutable.value = Status(true,done,total,token = token)
    }
    @Synchronized fun beginCommit(token: Long): Boolean {
        val s = mutable.value
        if (s.token != token || !s.running) return false
        mutable.value = s.copy(committing = true)
        return true
    }
    @Synchronized fun finish(context: Context, token: Long, session: DuplicateSession?,error: String? = null, cancelled: Boolean = false) {
        if (mutable.value.token != token || !mutable.value.running) return
        if (session != null || cancelled) mark(context,false)
        mutable.value = Status(session = session,error = error,token = token)
    }
    fun cancel(context: Context) {
        if (!state.value.running || state.value.committing) return
        runCatching { context.startService(Intent(context,MusicDuplicateScanService::class.java).setAction(MusicDuplicateScanService.CANCEL)) }
    }
}

class MusicDuplicateScanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var terminal = false
    private var cancelReason: String? = null
    private var explicitCancel = false
    private var token = 0L
    private var wake: PowerManager.WakeLock? = null
    private var lastNotice = 0L
    private val manager get() = getSystemService(NotificationManager::class.java)
    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"Поиск дубликатов",NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            if (DuplicateBackgroundScan.state.value.committing) return START_NOT_STICKY
            cancelReason = "Поиск отменён"; explicitCancel = true
            if (job?.isActive == true) job?.cancel() else { terminal = true; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); DuplicateBackgroundScan.finish(this,token,null,cancelReason,cancelled = true) }
            return START_NOT_STICKY
        }
        if (job != null && job?.isActive == true) return START_NOT_STICKY
        val request = DuplicateBackgroundScan.claim()
        token = request?.token ?: DuplicateBackgroundScan.state.value.token
        terminal = false; cancelReason = null; explicitCancel = false
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID,notice(0,request?.rows?.size ?: 0),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID,notice(0,request?.rows?.size ?: 0))
        if (request == null) { terminal = true; DuplicateBackgroundScan.finish(this,token,null,"Поиск прерван. Запустите его снова."); stopSelf(); return START_NOT_STICKY }
        val scanToken = request.token
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MediaLoader:duplicate-scan").apply {
            setReferenceCounted(false); acquire(6L*60*60*1000)
        }
        job = scope.launch {
            try {
                val result = MusicDuplicateScanner.scan(this@MusicDuplicateScanService,request.rows,request.linked,request.current) { done,total ->
                    DuplicateBackgroundScan.progress(scanToken,done,total)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (DuplicateBackgroundScan.state.value.token == scanToken && DuplicateBackgroundScan.state.value.running && (now - lastNotice >= 1000 || done == total)) {
                        lastNotice = now
                        notifyAllowed(ID,notice(done,total))
                    }
                }
                ensureActive()
                if (!DuplicateBackgroundScan.beginCommit(scanToken)) return@launch
                val session = DuplicateSession(result,emptySet(),result.groups.associate { it.key to MusicDuplicatePolicy.recommended(it) },0,0,System.currentTimeMillis())
                check(MusicDuplicateSessionStore.save(this@MusicDuplicateScanService,session)) { "Не удалось сохранить поиск" }
                terminal = true
                stopForeground(STOP_FOREGROUND_REMOVE)
                notifyAllowed(DONE_ID,notificationBase().setContentTitle("Поиск дубликатов завершён")
                    .setContentText("Групп: ${result.groups.size} · файлов: ${result.groups.sumOf { it.tracks.size }}")
                    .setAutoCancel(true).build())
                DuplicateBackgroundScan.finish(this@MusicDuplicateScanService,scanToken,session)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { terminal = true; DuplicateBackgroundScan.finish(this@MusicDuplicateScanService,scanToken,null,e.message ?: "Ошибка поиска") }
            finally {
                if (wake?.isHeld == true) wake?.release()
                wake = null
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                cancelReason?.let { terminal = true; DuplicateBackgroundScan.finish(this@MusicDuplicateScanService,scanToken,null,it,cancelled = explicitCancel) }
            }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelReason = "Поиск приостановлен Android. Продолжите при следующем открытии."; explicitCancel = false
        job?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        scope.cancel()
        if (wake?.isHeld == true) wake?.release()
        wake = null
        if (!terminal) DuplicateBackgroundScan.finish(this,token,null,"Поиск прерван. Проверенные файлы сохранены.")
        super.onDestroy()
    }
    private fun notifyAllowed(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runCatching { manager.notify(id,notification) }
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
        internal const val CANCEL = "cancel_duplicate_scan"
    }
}

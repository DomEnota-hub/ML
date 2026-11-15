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
    enum class Outcome(val stored:String){IDLE("idle"),RUNNING("running"),PAUSED("paused"),STOPPED("stopped"),COMPLETED("completed"),FAILED("failed"),INTERRUPTED("interrupted")}
    data class Request(val token: Long, val rows: List<DownloadEntity>, val linked: Set<String>, val current: String?,val expanded:Boolean=false)
    data class Status(val running: Boolean = false, val done: Int = 0, val total: Int = 0,
        val session: DuplicateSession? = null, val error: String? = null, val token: Long = 0, val committing: Boolean = false,
        val phase:DuplicateScanPhase=DuplicateScanPhase.METADATA,val startedAt:Long=0,val phaseStartedAt:Long=0,val partial:DuplicateScanResult?=null,
        val outcome:Outcome=Outcome.IDLE,val expanded:Boolean=false)
    private val mutable = MutableStateFlow(Status())
    val state = mutable.asStateFlow()
    private var pending: Request? = null
    private var nextToken = 0L
    private fun prefs(context: Context) = context.getSharedPreferences("duplicate_background_scan",Context.MODE_PRIVATE)
    fun interrupted(context: Context) = prefs(context).getBoolean("incomplete",false)
    fun outcome(context:Context):Outcome {
        val live=state.value
        if(live.outcome!=Outcome.IDLE)return live.outcome
        val stored=prefs(context).getString("journalStatus",null)
        return when(stored){"paused"->Outcome.PAUSED;"stopped"->Outcome.STOPPED;"failed"->Outcome.FAILED;
            "running","interrupted"->Outcome.INTERRUPTED;"completed"->Outcome.COMPLETED;
            else->if(interrupted(context))Outcome.INTERRUPTED else Outcome.IDLE}
    }
    fun journalStatus(context:Context):String=when(outcome(context)){
        Outcome.RUNNING->"Выполняется";Outcome.PAUSED->"Приостановлен";Outcome.STOPPED->"Остановлен";
        Outcome.FAILED,Outcome.INTERRUPTED->"Прерван";Outcome.COMPLETED->"Завершён";Outcome.IDLE->"Незавершённый поиск"
    }
    // Lock order matches DuplicatePrivacyGuard.revoke (privacy monitor, then service monitor).
    fun clearJournal(context:Context,includePartial:Boolean):Boolean=synchronized(DuplicatePrivacyGuard.lock){synchronized(this){
        if(mutable.value.running||mutable.value.committing)return@synchronized false
        if(!MusicDuplicateSessionStore.clearJournal(context,includePartial))return@synchronized false
        if(includePartial){
            prefs(context).edit().remove("incomplete").remove("expanded").remove("journalStatus").apply()
            mutable.value=Status(token=++nextToken)
        }else{
            val s=mutable.value
            if(s.outcome==Outcome.COMPLETED){prefs(context).edit().remove("journalStatus").apply();mutable.value=Status(token=++nextToken)}
            else mutable.value=s.copy(session=null)
        };true
    }}
    fun discardPartial(context:Context):Boolean=synchronized(DuplicatePrivacyGuard.lock){synchronized(this){
        if(mutable.value.running||mutable.value.committing)return@synchronized false
        if(!MusicDuplicateSessionStore.discardPartial(context))return@synchronized false
        prefs(context).edit().remove("incomplete").remove("expanded").remove("journalStatus").apply()
        mutable.value=Status(token=++nextToken);true
    }}
    fun resumeExpanded(context:Context)=prefs(context).getBoolean("expanded",false)
    private fun mark(context: Context, value: Boolean) { prefs(context).edit().putBoolean("incomplete",value).apply() }
    @Synchronized fun start(context: Context, rows: List<DownloadEntity>, linked: Set<String>, current: String?,expanded:Boolean=false) {
        if (mutable.value.running) return
        val token = ++nextToken
        pending = Request(token,rows.toList(),linked.toSet(),current,expanded)
        val now=android.os.SystemClock.elapsedRealtime()
        mutable.value = Status(running = true,total = rows.size,token = token,startedAt=now,phaseStartedAt=now,outcome=Outcome.RUNNING,expanded=expanded)
        prefs(context).edit().putBoolean("expanded",expanded).apply()
        prefs(context).edit().putString("journalStatus","running").apply()
        mark(context,true)
        try { ContextCompat.startForegroundService(context,Intent(context,MusicDuplicateScanService::class.java)) }
        catch (e: Exception) { pending = null; prefs(context).edit().putString("journalStatus","failed").apply(); mutable.value = Status(error = "Не удалось запустить фоновый поиск",token = token,outcome=Outcome.FAILED,expanded=expanded) }
    }
    @Synchronized fun invalidateForPrivacy() {
        pending = null
        mutable.value = Status(error = "Поиск сброшен после переноса в Личное",token = ++nextToken,outcome=Outcome.FAILED)
    }
    @Synchronized fun claim(): Request? = pending.also { pending = null }
    @Synchronized fun progress(token: Long, done: Int,total: Int) {
        if (mutable.value.token == token && mutable.value.running && !mutable.value.committing)
            mutable.value = mutable.value.copy(done=done,total=total)
    }
    @Synchronized fun phase(token:Long,phase:DuplicateScanPhase){
        val s=mutable.value;if(s.token==token&&s.running)mutable.value=s.copy(phase=phase,done=0,total=0,phaseStartedAt=android.os.SystemClock.elapsedRealtime())
    }
    @Synchronized fun partial(token:Long,result:DuplicateScanResult){
        val s=mutable.value;if(s.token==token&&s.running)mutable.value=s.copy(partial=result)
    }
    @Synchronized fun beginCommit(token: Long): Boolean {
        val s = mutable.value
        if (s.token != token || !s.running) return false
        mutable.value = s.copy(committing = true,phase=DuplicateScanPhase.SAVING)
        return true
    }
    @Synchronized fun finish(context: Context, token: Long, session: DuplicateSession?,error: String? = null, outcome:Outcome=if(session!=null)Outcome.COMPLETED else Outcome.FAILED) {
        if (mutable.value.token != token || !mutable.value.running) return
        val old=mutable.value
        mark(context,session==null)
        prefs(context).edit().putString("journalStatus",outcome.stored).apply()
        mutable.value = Status(session = session,error = error.takeIf{outcome==Outcome.FAILED},token = token,partial=if(session==null)old.partial else null,
            done=old.done,total=old.total,outcome=outcome,expanded=old.expanded)
    }
    fun pause(context:Context){
        if(!state.value.running||state.value.committing)return
        runCatching{context.startService(Intent(context,MusicDuplicateScanService::class.java).setAction(MusicDuplicateScanService.PAUSE))}
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
        if (intent?.action == CANCEL||intent?.action==PAUSE) {
            if (DuplicateBackgroundScan.state.value.committing) return START_NOT_STICKY
            explicitCancel=intent?.action==CANCEL
            cancelReason=if(explicitCancel)"Поиск остановлен. Проверенные файлы сохранены." else "Поиск приостановлен. Можно продолжить."
            if (job?.isActive == true) job?.cancel() else { terminal = true; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); DuplicateBackgroundScan.finish(this,token,null,cancelReason,outcome=if(explicitCancel)DuplicateBackgroundScan.Outcome.STOPPED else DuplicateBackgroundScan.Outcome.PAUSED) }
            return START_NOT_STICKY
        }
        if (job != null && job?.isActive == true) return START_NOT_STICKY
        val request = DuplicateBackgroundScan.claim()
        token = request?.token ?: DuplicateBackgroundScan.state.value.token
        terminal = false; cancelReason = null; explicitCancel = false
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID,notice(0,request?.rows?.size ?: 0),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID,notice(0,request?.rows?.size ?: 0))
        if (request == null) { terminal = true; DuplicateBackgroundScan.finish(this,token,null,outcome=DuplicateBackgroundScan.Outcome.INTERRUPTED); stopSelf(); return START_NOT_STICKY }
        val scanToken = request.token
        val scanAt=System.currentTimeMillis()
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MediaLoader:duplicate-scan").apply {
            setReferenceCounted(false); acquire(6L*60*60*1000)
        }
        job = scope.launch {
            try {
                val result = MusicDuplicateScanner.scan(this@MusicDuplicateScanService,request.rows,request.linked,request.current,request.expanded,
                    onPhase={DuplicateBackgroundScan.phase(scanToken,it)},onPartial={partial->
                        DuplicateBackgroundScan.partial(scanToken,partial)
                        MusicDuplicateSessionStore.savePartial(this@MusicDuplicateScanService,DuplicateSession(partial,emptySet(),emptyMap(),0,0,scanAt))
                    }) { done,total ->
                    DuplicateBackgroundScan.progress(scanToken,done,total)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (DuplicateBackgroundScan.state.value.token == scanToken && DuplicateBackgroundScan.state.value.running && (now - lastNotice >= 1000 || done == total)) {
                        lastNotice = now
                        notifyAllowed(ID,notice(done,total))
                    }
                }
                ensureActive()
                if (!DuplicateBackgroundScan.beginCommit(scanToken)) return@launch
                val session = DuplicateSession(result,emptySet(),result.groups.associate { it.key to MusicDuplicatePolicy.recommended(it) },0,0,scanAt)
                check(MusicDuplicateSessionStore.save(this@MusicDuplicateScanService,session)) { "Не удалось сохранить поиск" }
                synchronized(DuplicatePrivacyGuard.lock){DuplicateReportJournal.clearPartial(this@MusicDuplicateScanService,"music")}
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
                cancelReason?.let { terminal = true; DuplicateBackgroundScan.finish(this@MusicDuplicateScanService,scanToken,null,it,outcome=if(explicitCancel)DuplicateBackgroundScan.Outcome.STOPPED else DuplicateBackgroundScan.Outcome.PAUSED) }
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
        if (!terminal) DuplicateBackgroundScan.finish(this,token,null,outcome=DuplicateBackgroundScan.Outcome.INTERRUPTED)
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
        val phase=DuplicateBackgroundScan.state.value.phase
        val cancel = PendingIntent.getService(this,ID,Intent(this,MusicDuplicateScanService::class.java).setAction(CANCEL),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return notificationBase().setContentTitle("Поиск дубликатов треков").setContentText("${phase.caption} · $done из $total")
            .setProgress(total,done,total == 0).setOngoing(true).addAction(0,"Отменить",cancel).build()
    }
    companion object {
        private const val CHANNEL = "duplicate_scan"
        private const val ID = 8293
        private const val DONE_ID = 8294
        internal const val PAUSE = "pause_music_duplicates"
        internal const val CANCEL = "cancel_duplicate_scan"
    }
}

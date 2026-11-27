package com.domenota.medialoader.data.tasks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.BatteryManager
import android.content.Intent
import android.content.IntentFilter
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class TaskHistoryData(val clearedAt: Long=0,val rows: List<TaskSnapshot> = emptyList())
internal data class TaskSettings(val retention: Int=7,val charging: Boolean=false,val batterySaver: Boolean=true,val notifications: Boolean=false) {
    companion object { val RETENTION=listOf(7,14,30,90,0) }
}
internal object TaskHistoryStore {
    private val mutable=MutableStateFlow(TaskSettings())
    val settings=mutable.asStateFlow()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var app: Context?=null
    private val ready=CompletableDeferred<Boolean>()
    private val diskGate=Mutex()
    @Volatile private var historyFile:AtomicFile?=null
    @Synchronized fun start(context: Context, registry: TaskRegistry) {
        if(app!=null)return
        app=context
        val prefs=context.getSharedPreferences("task-center-settings",Context.MODE_PRIVATE)
        mutable.value=TaskSettings(prefs.getInt("retention",7).takeIf{it in TaskSettings.RETENTION}?:7,
            prefs.getBoolean("charging",false),prefs.getBoolean("batterySaver",true),prefs.getBoolean("notifications",false))
        registry.configure(mutable.value.retention) { row -> scope.launch { notifyCompletion(context,row) } }
        val file=AtomicFile(File(context.noBackupFilesDir,"task-center-history.json"))
        historyFile=file
        scope.launch {
            val restored=try { decode(JSONObject(file.readFully().toString(Charsets.UTF_8))) }
            catch(_:java.io.FileNotFoundException) {
                if(file.baseFile.exists()||File(file.baseFile.path+".bak").exists()||File(file.baseFile.path+".new").exists())null else TaskHistoryData()
            } catch(_:Exception){null}
            if(restored==null){
                com.domenota.medialoader.core.logging.AppLog.w("TaskCenter","History unavailable · existing data retained")
                ready.complete(false)
                return@launch
            }
            try { registry.restore(restored) } catch(_:Exception){ ready.complete(false);return@launch }
            ready.complete(true)
            registry.history.collect { persistLatest(registry) }
        }
    }
    suspend fun awaitReady():Boolean=ready.await()
    /** Terminal checkpoints may only be deleted after the latest history is durable.
     * Background collector and explicit flush share one writer gate. */
    suspend fun persistCurrent(registry:TaskRegistry):Boolean = awaitReady() && persistLatest(registry)
    private suspend fun persistLatest(registry:TaskRegistry):Boolean=withContext(Dispatchers.IO) {
        diskGate.withLock {
            val file=historyFile ?: return@withLock false
            val bytes=encode(registry.history.value).toString().toByteArray(Charsets.UTF_8)
            runCatching {
                val out=file.startWrite()
                try { out.write(bytes);file.finishWrite(out) } catch(e:Exception){file.failWrite(out);throw e}
            }.onFailure{com.domenota.medialoader.core.logging.AppLog.w("TaskCenter","History write failed")}.isSuccess
        }
    }
    fun update(value: TaskSettings) {
        require(value.retention in TaskSettings.RETENTION)
        val context=app?:return
        context.getSharedPreferences("task-center-settings",Context.MODE_PRIVATE).edit()
            .putInt("retention",value.retention).putBoolean("charging",value.charging)
            .putBoolean("batterySaver",value.batterySaver).putBoolean("notifications",value.notifications).apply()
        mutable.value=value; TaskCenter.registry.configure(value.retention)
    }
    fun waitReason(context: Context): TaskWait? {
        val s=mutable.value
        if(s.charging) {
            val battery=context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status=battery?.getIntExtra(BatteryManager.EXTRA_STATUS,-1)
            if(status!=BatteryManager.BATTERY_STATUS_CHARGING && status!=BatteryManager.BATTERY_STATUS_FULL) return TaskWait.CHARGING
        }
        if(s.batterySaver && context.getSystemService(PowerManager::class.java)?.isPowerSaveMode==true) return TaskWait.RESOURCE
        return null
    }
    private fun notifyCompletion(context: Context,row:TaskSnapshot) {
        if(!settings.value.notifications || row.kind !in setOf(TaskKind.EXPORT,TaskKind.BATCH_EDIT,TaskKind.OCR,TaskKind.MODEL))return
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return
        runCatching {
            val manager=context.getSystemService(NotificationManager::class.java)
            val channel="task-center-completion"
            manager.createNotificationChannel(NotificationChannel(channel,"Завершение задач",NotificationManager.IMPORTANCE_DEFAULT))
            manager.notify(row.id.hashCode(),Notification.Builder(context,channel)
                .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle(row.kind.label)
                .setContentText(row.status.label).setAutoCancel(true).build())
        }
    }
    internal fun encode(data:TaskHistoryData)=JSONObject().put("clearedAt",data.clearedAt).put("rows",JSONArray(data.rows.filter{it.status.terminal}.map { row ->
        JSONObject().put("id",row.id).put("kind",row.kind.name).put("status",row.status.name).put("time",row.updatedAt)
            .put("errors",row.errors).put("percent",row.percent?:JSONObject.NULL).put("stage",row.stage?.name?:JSONObject.NULL)
            .put("files",progressJson(row.files)).put("bytes",progressJson(row.progress))
            .put("details",detailsJson(row.details))
    }))
    private fun detailsJson(value:TaskDetails?):Any = value?.let { d ->
        JSONObject().put("correlation",d.correlation?.value?:JSONObject.NULL)
            .put("started",d.startedAt?:JSONObject.NULL).put("trigger",d.trigger?.name?:JSONObject.NULL)
            .put("failure",d.failure?.name?:JSONObject.NULL).put("recovered",d.recovered).put("checkpoints",d.checkpoints?.let(TaskCheckpointCodec::encode)?:JSONObject.NULL).put("outcomes",d.outcomes?.let { c ->
                JSONObject().put("cached",c.cached).put("generated",c.generated).put("skipped",c.skipped).put("failed",c.failed)
            }?:JSONObject.NULL)
    }?:JSONObject.NULL
    private fun decodeDetails(o:JSONObject?):TaskDetails? = o?.let { d ->
        TaskDetails(TaskCorrelation.parse(d.optString("correlation")),
            d.optLong("started",-1).takeIf{it>=0},
            TaskTrigger.entries.firstOrNull{it.name==d.optString("trigger")},
            TaskFailure.entries.firstOrNull{it.name==d.optString("failure")},
            d.optJSONObject("outcomes")?.let { c -> runCatching { TaskOutcomes(c.getInt("cached"),c.getInt("generated"),c.getInt("skipped"),c.getInt("failed")) }.getOrNull() },d.optBoolean("recovered",false),TaskCheckpointCodec.decode(d.optJSONObject("checkpoints")))
    }
    private fun progressJson(value:TaskProgress?):Any = value?.let { JSONObject().put("done",it.done).put("total",it.total?:JSONObject.NULL) }?:JSONObject.NULL
    internal fun decode(json:JSONObject):TaskHistoryData {
        val array=json.optJSONArray("rows")?:JSONArray()
        val rows=(0 until array.length()).mapNotNull { index -> runCatching {
            val o=array.getJSONObject(index)
            val status=TaskStatus.valueOf(o.getString("status"))
            require(status.terminal)
            fun progress(key:String)=o.optJSONObject(key)?.let { TaskProgress(it.getLong("done"),if(it.isNull("total"))null else it.getLong("total")) }
            TaskSnapshot(o.getString("id"),TaskKind.valueOf(o.getString("kind")),status,progress=progress("bytes"),files=progress("files"),
                errors=o.getInt("errors"),updatedAt=o.getLong("time"),percent=if(o.isNull("percent"))null else o.getInt("percent"),
                stage=if(o.isNull("stage"))null else TaskStage.valueOf(o.getString("stage")),details=decodeDetails(o.optJSONObject("details")))
        }.getOrNull() }
        return TaskHistoryData(json.optLong("clearedAt",0).coerceAtLeast(0),rows)
    }
}

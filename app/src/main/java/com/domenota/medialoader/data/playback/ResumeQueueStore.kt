package com.domenota.medialoader.data.playback

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bounded local queue checkpoints; writes are serial and atomic, outside the player thread.
 * Stored outside Android backup. No titles/artwork or private playback paths. */
internal class ResumeQueueStore private constructor(context:Context,fileName:String="music-resume-v1.json") {
    private val file=AtomicFile(File(context.applicationContext.noBackupFilesDir,fileName))
    private val mutex=Mutex()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val state=MutableStateFlow<List<ResumeQueueSession>>(emptyList())
    val sessions=state.asStateFlow()
    private val error=MutableStateFlow(false)
    val failed=error.asStateFlow()
    private val revisions=java.util.concurrent.ConcurrentHashMap<String,Long>()
    fun lease(id:String):Long=revisions[id] ?: 0L
    private var loaded=false
    private sealed interface Command {
        data class Save(val value:ResumeQueueSession,val lease:Long):Command
        data class Finish(val id:String,val lease:Long):Command
    }
    private val commands=Channel<Command>(Channel.UNLIMITED)
    init {
        scope.launch {
            try {mutex.withLock{load()}} catch(_:Exception){error.value=true}
            for(command in commands) {
                try {when(command){is Command.Save->save(command.value,command.lease);is Command.Finish->remove(command.id,command.lease)};error.value=false}
                catch(e:CancellationException){throw e}
                catch(_:Exception){error.value=true}
            }
        }
    }
    fun submit(snapshot:ResumeQueueSession,lease:Long=this.lease(snapshot.id)) {commands.trySend(Command.Save(snapshot,lease))}
    fun finish(id:String,lease:Long=this.lease(id)) {commands.trySend(Command.Finish(id,lease))}
    private fun load() {
        if(loaded)return
        if(file.baseFile.exists()||File(file.baseFile.path+".bak").exists()) {
            val json=JSONObject(String(file.readFully(),Charsets.UTF_8))
            require(json.getInt("version")==1){"Неизвестный формат сеансов"}
            val records=json.getJSONArray("sessions")
            state.value=(0 until records.length()).map { i ->
                val row=records.getJSONObject(i);val queue=row.getJSONArray("entries")
                val entries=(0 until queue.length()).map { j ->
                    val a=queue.getJSONArray(j);(0 until a.length()).map{a.getString(it)}.toSet()
                }
                ResumeQueueSession(row.getString("id"),row.optString("playlistId").takeIf{it.isNotBlank()&&it!="null"},
                    entries,row.getInt("index"),row.getLong("position"),row.getLong("duration"),row.getBoolean("shuffle"),
                    row.getInt("repeat"),row.getDouble("speed").toFloat(),row.getLong("updated"),
                    row.optJSONArray("occurrences")?.let { ids -> (0 until ids.length()).map { ids.getString(it) } }
                        ?: entries.indices.map { "${row.getString("id")}:$it" })
            }.distinctBy{it.id}.take(ResumeQueuePolicy.MAX_SESSIONS)
        }
        loaded=true
    }
    private fun write(records:List<ResumeQueueSession>) {
        val array=JSONArray()
        records.forEach { r ->
            val queue=JSONArray();r.entries.forEach{queue.put(JSONArray(it.sorted()))}
            array.put(JSONObject().put("id",r.id).put("playlistId",r.playlistId ?: JSONObject.NULL)
                .put("entries",queue).put("occurrences",JSONArray(r.occurrenceIds)).put("index",r.index).put("position",r.positionMs).put("duration",r.durationMs)
                .put("shuffle",r.shuffle).put("repeat",r.repeat).put("speed",r.speed.toDouble()).put("updated",r.updatedAt))
        }
        val out=file.startWrite()
        try {out.write(JSONObject().put("version",1).put("sessions",array).toString().toByteArray(Charsets.UTF_8));file.finishWrite(out)}
        catch(e:Exception){file.failWrite(out);throw e}
        state.value=records
    }
    internal suspend fun save(snapshot:ResumeQueueSession,lease:Long?=null)=withContext(Dispatchers.IO){mutex.withLock{
        load();if(lease==null || lease==this@ResumeQueueStore.lease(snapshot.id)) {
            val result=ResumeQueuePolicy.keep(state.value,snapshot);if(result!=state.value)write(result)
        }
    }}
    internal suspend fun remove(id:String,lease:Long?=null)=withContext(Dispatchers.IO){mutex.withLock{
        load();if(lease==null || lease==this@ResumeQueueStore.lease(id)) {
            val result=state.value.filterNot{it.id==id};if(result!=state.value)write(result)
        }
    }}
    /** Optimistic equality prevents a stale editor from overwriting a newer checkpoint.
     * Successful edits invalidate old recorder leases without touching its player. */
    suspend fun edit(expected:ResumeQueueSession,remove:Set<String>,resolved:ResolvedResumeQueue)=withContext(Dispatchers.IO){mutex.withLock {
        load();check(state.value.firstOrNull { it.id==expected.id }==expected) { "Сеанс изменился. Откройте очередь заново" }
        val replacement=ResumeQueuePolicy.removeOccurrences(resolved,remove)
        val result=state.value.mapNotNull { if(it.id==expected.id)replacement else it }
        if(result!=state.value) { write(result);revisions[expected.id]=lease(expected.id)+1 }
    }}
    suspend fun deleteSaved(id:String)=withContext(Dispatchers.IO){mutex.withLock {
        load();write(state.value.filterNot { it.id==id });revisions[id]=lease(id)+1
    }}
    suspend fun clearSaved()=withContext(Dispatchers.IO){mutex.withLock {
        load();val ids=state.value.map { it.id };write(emptyList());ids.forEach { revisions[it]=lease(it)+1 }
    }}
    internal suspend fun readForTest()=withContext(Dispatchers.IO){mutex.withLock{load();state.value}}
    internal fun closeForTest(){commands.close();scope.cancel()}
    companion object {
        @Volatile private var instance:ResumeQueueStore?=null
        fun get(context:Context):ResumeQueueStore=instance?:synchronized(this){instance?:ResumeQueueStore(context).also{instance=it}}
        internal fun forTest(context:Context,fileName:String)=ResumeQueueStore(context,fileName)
    }
}

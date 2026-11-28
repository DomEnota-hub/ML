package com.domenota.medialoader.data.recognition

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.domenota.medialoader.core.recognition.*
import com.domenota.medialoader.data.library.DuplicatePrivacyGuard
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A result belongs to verified bytes at one public locator, not a title or queue position. */
internal data class TrackRecognitionVersion(val id:String,val uri:String,val sha256:String,val privacyEpoch:Long)
internal data class TrackRecognitionMatch(val version:TrackRecognitionVersion,val result:MusicRecognitionResult, val verification:TrackVerification=TrackVerification.FIRST_MATCH)
internal data class TrackRecognitionOutcome(val match:TrackRecognitionMatch?,val nextIndex:Int,val total:Int,val conflict:Boolean=false,val retried:Boolean=false)
internal enum class TrackRecognitionStage { DECODING, SEARCHING }
internal data class TrackRecognitionProgress(val index:Int,val total:Int,val stage:TrackRecognitionStage,val startMs:Long?=null,val durationMs:Long?=null)
internal object TrackRecognitionCache {
    private const val VERSION=1
    private const val LIMIT=128
    private fun root(context:Context)=File(context.noBackupFilesDir,"track-recognition-v$VERSION")
    private fun key(version:TrackRecognitionVersion)=MessageDigest.getInstance("SHA-256")
        .digest((version.uri+"\u0000"+version.sha256).toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    private fun file(context:Context,version:TrackRecognitionVersion)=AtomicFile(File(root(context).apply{mkdirs()},key(version)+".json"))
    fun clearForPrivacy(context:Context){check(root(context).deleteRecursively()){"Не удалось очистить кеш распознавания"}}
    fun load(context:Context,version:TrackRecognitionVersion):MusicRecognitionResult?=synchronized(DuplicatePrivacyGuard.lock){
        if(!DuplicatePrivacyGuard.accepts(context,version.privacyEpoch,version.id))return@synchronized null
        runCatching{
            val target=file(context,version)
            val json=JSONObject(target.openRead().bufferedReader().use{it.readText()})
            require(json.getInt("version")==VERSION&&json.getString("sha")==version.sha256)
            fun optional(name:String)=if(json.isNull(name))null else json.getString(name).takeIf(String::isNotBlank)
            MusicRecognitionResult(optional("track"),json.getString("title"),json.getString("artist"),optional("album"),optional("artwork"),
                RecognitionSource.valueOf(json.getString("source"))).also{
                require(it.title.isNotBlank()&&it.artist.isNotBlank())
                target.baseFile.setLastModified(System.currentTimeMillis())
            }
        }.getOrNull()
    }
    fun save(context:Context,version:TrackRecognitionVersion,result:MusicRecognitionResult):Boolean=synchronized(DuplicatePrivacyGuard.lock){
        if(!DuplicatePrivacyGuard.accepts(context,version.privacyEpoch,version.id))return@synchronized false
        runCatching{
            val json=JSONObject().put("version",VERSION).put("sha",version.sha256)
                .put("track",result.trackId?:JSONObject.NULL).put("title",result.title).put("artist",result.artist)
                .put("album",result.album?:JSONObject.NULL).put("artwork",result.artworkUrl?:JSONObject.NULL).put("source",result.source.name)
            val target=file(context,version);val stream=target.startWrite()
            try{stream.write(json.toString().toByteArray(Charsets.UTF_8));target.finishWrite(stream)}
            catch(e:Exception){target.failWrite(stream);throw e}
            root(context).listFiles().orEmpty().filter{it.name.endsWith(".json")}.sortedByDescending{it.lastModified()}.drop(LIMIT).forEach{AtomicFile(it).delete()}
        }.isSuccess
    }
    // Separate journal so the old success-only cache cannot restore a rejected result.
    private fun journalFile(context: Context, version: TrackRecognitionVersion) = AtomicFile(
        File(File(root(context), "sessions").apply { mkdirs() }, key(version) + ".json"))
    private fun resultJson(result: MusicRecognitionResult) = JSONObject()
        .put("track",result.trackId?:JSONObject.NULL).put("title",result.title).put("artist",result.artist)
        .put("album",result.album?:JSONObject.NULL).put("artwork",result.artworkUrl?:JSONObject.NULL)
        .put("source",result.source.name)
    private fun parseResult(json: JSONObject): MusicRecognitionResult {
        fun opt(name:String)=if(json.isNull(name))null else json.getString(name).takeIf(String::isNotBlank)
        return MusicRecognitionResult(opt("track"),json.getString("title"),json.getString("artist"),
            opt("album"),opt("artwork"),RecognitionSource.valueOf(json.getString("source")))
    }
    fun loadRetry(context: Context, version: TrackRecognitionVersion): TrackRetryState? = synchronized(DuplicatePrivacyGuard.lock) {
        if (!DuplicatePrivacyGuard.accepts(context,version.privacyEpoch,version.id)) return@synchronized null
        val target = journalFile(context,version)
        // AtomicFile.openRead restores its backup after an interrupted write.
        // A corrupt journal is not silently reset: it could contain a rejected match.
        val raw=try{target.openRead().bufferedReader().use{it.readText()}}
            catch(_:java.io.FileNotFoundException){return@synchronized null}
        val json=JSONObject(raw)
        require(json.getString("sha")==version.sha256)
        val starts=json.getJSONArray("starts").let{ a -> List(a.length()){a.getLong(it)} }
        val checked=json.getJSONArray("checked").let{ a -> List(a.length()){
            val w=a.getJSONObject(it);CheckedAudioWindow(w.getLong("start"),w.getLong("duration")) } }
        val rejected=json.getJSONArray("rejected").let{ a -> List(a.length()){parseResult(a.getJSONObject(it))} }
        require(starts.size in 1..5 && starts.distinct().size==starts.size && starts.all{it>=0} &&
            checked.size<=starts.size && checked.map{it.startMs}.distinct().size==checked.size &&
            checked.all{it.startMs in starts && it.durationMs in 0..13_000} && rejected.size<=5)
        val candidate=json.optJSONObject("candidate")?.let{ c ->
            val window=checked.single{it.startMs==c.getLong("start")}
            RetryCandidate(parseResult(c.getJSONObject("result")),window,TrackVerification.valueOf(c.getString("verification")))
        }
        require(candidate==null || rejected.none{rejectedRecognition(it,candidate.result)})
        target.baseFile.setLastModified(System.currentTimeMillis())
        TrackRetryState(starts,checked,rejected,candidate,json.optBoolean("conflict"))
    }
    fun saveRetry(context: Context, version: TrackRecognitionVersion, state: TrackRetryState): Boolean = synchronized(DuplicatePrivacyGuard.lock) {
        if (!DuplicatePrivacyGuard.accepts(context,version.privacyEpoch,version.id)) return@synchronized false
        runCatching {
            val json=JSONObject().put("id",version.id).put("sha",version.sha256)
                .put("starts",org.json.JSONArray(state.starts))
                .put("checked",org.json.JSONArray(state.checked.map{JSONObject().put("start",it.startMs).put("duration",it.durationMs)}))
                .put("rejected",org.json.JSONArray(state.rejected.map(::resultJson)))
                .put("conflict",state.conflict)
            state.candidate?.let{json.put("candidate",JSONObject().put("result",resultJson(it.result))
                .put("start",it.window.startMs).put("verification",it.verification.name))}
            val target=journalFile(context,version);val stream=target.startWrite()
            try{stream.write(json.toString().toByteArray(Charsets.UTF_8));target.finishWrite(stream)}
            catch(e:Exception){target.failWrite(stream);throw e}
            // Rejected decisions must not be evicted into a repeated incorrect cache hit.
            // Only successful/unused journals without a rejection may be pruned.
            target.baseFile.parentFile?.listFiles().orEmpty().filter{it.name.endsWith(".json") && it!=target.baseFile}
                .sortedByDescending{it.lastModified()}.drop(LIMIT-1).forEach{old->
                    val safe=runCatching{JSONObject(AtomicFile(old).openRead().bufferedReader().use{it.readText()})
                        .getJSONArray("rejected").length()==0}.getOrDefault(false)
                    if(safe)AtomicFile(old).delete()
                }
        }.isSuccess
    }
    suspend fun sha256(context:Context,uri:String):String=withContext(Dispatchers.IO){
        val value=Uri.parse(uri)
        val input=when(value.scheme){
            "file"->File(requireNotNull(value.path)).inputStream()
            "content"->context.contentResolver.openInputStream(value)?:error("Файл недоступен")
            else->error("Файл недоступен")
        }
        digest(input)
    }
    suspend fun sha256(file:File):String=withContext(Dispatchers.IO){digest(file.inputStream())}
    private suspend fun digest(input:InputStream):String{
        val hash=MessageDigest.getInstance("SHA-256");var size=0L
        input.use{stream->
            val buffer=ByteArray(128*1024)
            while(true){currentCoroutineContext().ensureActive();val n=stream.read(buffer);if(n<0)break;hash.update(buffer,0,n);size+=n}
        }
        check(size>0){"Файл пуст"}
        return hash.digest().joinToString(""){"%02x".format(it)}
    }
}

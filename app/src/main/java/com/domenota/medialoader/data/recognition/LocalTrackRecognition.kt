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
internal data class TrackRecognitionMatch(val version:TrackRecognitionVersion,val result:MusicRecognitionResult)
internal data class TrackRecognitionOutcome(val match:TrackRecognitionMatch?,val nextIndex:Int,val total:Int)
internal enum class TrackRecognitionStage { DECODING, SEARCHING }
internal data class TrackRecognitionProgress(val index:Int,val total:Int,val stage:TrackRecognitionStage)
internal data class WindowRecognitionResult(val result:MusicRecognitionResult?,val nextIndex:Int)

internal object LocalTrackRecognitionSearch {
    suspend fun find(starts:List<Long>,startIndex:Int,maxAttempts:Int,
        decode:suspend (Long)->ShortArray,provider:MusicRecognitionProvider,
        onProgress:suspend (TrackRecognitionProgress)->Unit={}):WindowRecognitionResult {
        require(startIndex>=0&&maxAttempts>0)
        var index=startIndex.coerceAtMost(starts.size)
        val end=minOf(starts.size,index+maxAttempts)
        while(index<end){
            currentCoroutineContext().ensureActive()
            onProgress(TrackRecognitionProgress(index,starts.size,TrackRecognitionStage.DECODING))
            val pcm=decode(starts[index])
            if(RecognitionAudio.eligible(pcm)){
                onProgress(TrackRecognitionProgress(index,starts.size,TrackRecognitionStage.SEARCHING))
                val result=provider.recognize(pcm)
                if(result!=null&&result.title.isNotBlank()&&result.artist.isNotBlank())
                    return WindowRecognitionResult(result,index+1)
            }
            index++
        }
        return WindowRecognitionResult(null,index)
    }
}

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

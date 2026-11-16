package com.domenota.medialoader.data.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.model.DownloadState
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import java.io.File
import java.security.MessageDigest
import android.util.AtomicFile
import org.json.JSONObject
import org.json.JSONArray
import com.domenota.medialoader.data.playback.PublicMediaIdentity

internal data class VisualScanResult(val groups:List<VisualDuplicateGroup>,val skipped:Int,val privacyEpoch:Long=0, val coverage:VisualScanCoverage?=null)
internal object VisualDuplicateScanner {
    private data class Stamp(val bytes:Long,val modified:Long?)
    private fun directory(context:Context)=File(context.noBackupFilesDir,"visual-duplicate-cache-v1")
    private fun key(uri:String)=MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString(""){"%02x".format(it)}+".json"
    fun clearForPrivacy(context:Context){check(directory(context).deleteRecursively()){"Не удалось очистить кеш Галереи"}}
    private fun checkpoint(context:Context,uri:String)=AtomicFile(File(directory(context).apply{mkdirs()},key(uri)))
    private fun cached(context:Context,ref:LibraryMediaRef,uri:String,stamp:Stamp,epoch:Long):VisualFingerprint?=synchronized(DuplicatePrivacyGuard.lock){
        if(stamp.modified==null||!DuplicatePrivacyGuard.accepts(context,epoch,ref.key))return@synchronized null
        runCatching{
            val data=JSONObject(checkpoint(context,uri).openRead().bufferedReader().use{it.readText()})
            require(data.getLong("epoch")==epoch&&data.getString("id")==ref.key&&data.getString("uri")==uri&&data.getLong("bytes")==stamp.bytes&&data.getLong("modified")==stamp.modified)
            val frames=data.getJSONArray("frames");require(frames.length()==(if(ref.item.mediaType==MediaType.PHOTO)1 else 5))
            VisualFingerprint(ref.key,uri,MediaType.valueOf(data.getString("type")),stamp.bytes,stamp.modified,data.getString("sha"),data.getInt("width"),data.getInt("height"),
                if(data.isNull("duration"))null else data.getLong("duration"),(0 until frames.length()).map{frames.getLong(it)},data.getBoolean("informative"))
                .takeIf{it.type==ref.item.mediaType&&it.sha.isNotBlank()}
        }.getOrNull()
    }
    private fun save(context:Context,f:VisualFingerprint,epoch:Long)=synchronized(DuplicatePrivacyGuard.lock){
        check(DuplicatePrivacyGuard.accepts(context,epoch,f.id)){"Поиск устарел"}
        if(f.modified==null)return@synchronized
        // Failed/incomplete perceptual decodes remain retryable.
        if(f.frames.size!=(if(f.type==MediaType.PHOTO)1 else 5))return@synchronized
        val file=checkpoint(context,f.uri)
        val data=JSONObject().put("epoch",epoch).put("id",f.id).put("uri",f.uri).put("type",f.type.name)
            .put("bytes",f.bytes).put("modified",f.modified).put("sha",f.sha).put("width",f.width).put("height",f.height)
            .put("duration",f.durationMs?:JSONObject.NULL).put("frames",JSONArray(f.frames)).put("informative",f.informative)
        runCatching{val out=file.startWrite();try{out.write(data.toString().toByteArray());file.finishWrite(out)}catch(e:Exception){file.failWrite(out);throw e}}
        Unit
    }
    private fun stamp(context:Context,uri:Uri):Stamp {
        if(uri.scheme=="file"){
            val f=File(requireNotNull(uri.path));check(f.isFile&&f.length()>0)
            return Stamp(f.length(),f.lastModified().takeIf{it>0})
        }
        require(uri.scheme=="content")
        return context.contentResolver.query(uri,arrayOf("_size","date_modified"),null,null,null)?.use{
            check(it.moveToFirst()&&it.getLong(0)>0)
            Stamp(it.getLong(0),if(it.isNull(1))null else it.getLong(1)*1000)
        }?:error("File metadata unavailable")
    }
    private suspend fun sha(context:Context,uri:Uri):String {
        val digest=MessageDigest.getInstance("SHA-256")
        requireNotNull(context.contentResolver.openInputStream(uri)).use{input->
            val buffer=ByteArray(128*1024)
            while(true){coroutineContext.ensureActive();val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}
        }
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
    private data class Frame(val hash:Long,val informative:Boolean)
    private fun frame(bitmap:Bitmap):Frame {
        val small=Bitmap.createScaledBitmap(bitmap,9,8,true)
        try {
            val pixels=IntArray(72);small.getPixels(pixels,0,9,0,0,9,8)
            val gray=pixels.map{p->(299*((p shr 16) and 255)+587*((p shr 8) and 255)+114*(p and 255))/1000}
            var hash=0L
            for(y in 0..7)for(x in 0..7)if(gray[y*9+x]>gray[y*9+x+1])hash=hash or (1L shl (y*8+x))
            return Frame(hash,gray.maxOrNull()!!-gray.minOrNull()!!>=24&&java.lang.Long.bitCount(hash) in 5..59)
        }finally{if(small!==bitmap)small.recycle()}
    }
    private suspend fun read(context:Context,ref:LibraryMediaRef,metrics:DuplicateScanMetrics,epoch:Long):Pair<VisualFingerprint,Boolean> {
        val row=ref.item;val uri=Uri.parse(requireNotNull(row.savedUri));val before=metrics.measure("metadata_stamp"){stamp(context,uri)}
        metrics.measure("checkpoint_read"){cached(context,ref,uri.toString(),before,epoch)}?.let{metrics.hit("visual_cache");return it to true}
        val hash=metrics.measure("sha256"){sha(context,uri)}
        var width=0;var height=0;var duration:Long?=null;val frames=mutableListOf<Frame>()
        try { metrics.measure(if(row.mediaType==MediaType.PHOTO)"photo_decode" else "video_frames") {
            if(row.mediaType==MediaType.PHOTO){
                val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                context.contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,bounds)}
                width=bounds.outWidth;height=bounds.outHeight
                var sample=1
                while(maxOf(width,height)/sample>512)sample*=2
                val bitmap=context.contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=sample})}
                bitmap?.let{try{frames+=frame(it)}finally{it.recycle()}}
            }else{
                val retriever=MediaMetadataRetriever()
                try{
                    retriever.setDataSource(context,uri)
                    width=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?:0
                    height=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?:0
                    duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    val length=duration?:0L
                    if(length>0&&android.os.Build.VERSION.SDK_INT>=27){
                        for(percent in listOf(10,30,50,70,90)){
                            coroutineContext.ensureActive()
                            val bitmap=retriever.getScaledFrameAtTime(length*percent*10,MediaMetadataRetriever.OPTION_CLOSEST,160,90)
                            bitmap?.let{try{frames+=frame(it)}finally{it.recycle()}}
                        }
                    }
                }finally{retriever.release()}
            }
        }}catch(e:CancellationException){throw e}catch(_:Exception){frames.clear()}
        check(before==stamp(context,uri)){"File changed during scan"}
        return VisualFingerprint(ref.key,uri.toString(),row.mediaType,before.bytes,before.modified,hash,width,height,duration,
            frames.map{it.hash},frames.isNotEmpty()&&frames.all{it.informative}).also{f->metrics.measure("checkpoint_write"){save(context,f,epoch)}} to false
    }
    /** Cheap catalog reconciliation is not authorization to trash a file. */
    fun matchesCatalog(snapshot:VisualFingerprint,ref:LibraryMediaRef):Boolean {
        val row=ref.item
        return ref.key==snapshot.id && row.savedUri==snapshot.uri &&
            row.mediaType==snapshot.type && row.state==DownloadState.COMPLETED &&
            !row.isPrivate && !row.hidden && row.sizeBytes==snapshot.bytes
    }

    /** Re-read the entire file immediately before a destructive operation.
     * A missing timestamp is not treated as proof of identity. */
    suspend fun verify(context:Context,snapshot:VisualFingerprint,ref:LibraryMediaRef):Boolean=withContext(Dispatchers.IO){
        if(!matchesCatalog(snapshot,ref)||snapshot.sha.isBlank())return@withContext false
        try {
            val uri=Uri.parse(snapshot.uri)
            val before=stamp(context,uri)
            if(before.bytes!=snapshot.bytes || before.modified!=snapshot.modified)return@withContext false
            val hash=sha(context,uri)
            hash==snapshot.sha && before==stamp(context,uri)
        }catch(e:CancellationException){throw e}catch(_:Exception){false}
    }

    suspend fun scan(context:Context,refs:List<LibraryMediaRef>,source:LibrarySource=LibrarySource.PHONE,
        expectedEpoch:Long=DuplicatePrivacyGuard.generation(context),onPhase:(DuplicateScanPhase)->Unit={},
        onPartial:(VisualScanResult)->Unit={},progress:(VisualScanCoverage)->Unit={}):VisualScanResult=withContext(Dispatchers.IO){
        val candidates=VisualDuplicateCatalog.candidates(refs)
        val metrics=DuplicateScanMetrics("gallery")
        val epoch=expectedEpoch
        check(DuplicatePrivacyGuard.generation(context)==epoch){"Поиск устарел"}
        // A narrower ML-only scan must not delete fingerprints of other phone files.
        // Prune stale entries only when scanning the complete accessible phone scope.
        if(source==LibrarySource.PHONE) synchronized(DuplicatePrivacyGuard.lock){
            check(DuplicatePrivacyGuard.generation(context)==epoch){"Поиск устарел"}
            val allowed=candidates.mapNotNull{it.item.savedUri}.map(::key).toSet()
            directory(context).listFiles().orEmpty().filter{it.name !in allowed}.forEach{it.delete()}
        }
        val rows=mutableListOf<VisualFingerprint>();val seen=mutableSetOf<String>()
        var coverage=VisualScanCoverage(source,candidates.size)
        onPhase(DuplicateScanPhase.VISUAL)
        progress(coverage)
        onPartial(VisualScanResult(emptyList(),0,epoch,coverage))
        fun publishChecked() {
            if(DuplicatePrivacyGuard.generation(context)!=epoch)return
            val exact=rows.filter{it.sha.isNotBlank()}.groupBy{Triple(it.type,it.bytes,it.sha)}.values.filter{it.size>1}.map{VisualDuplicateGroup(it,VisualMatch.EXACT)}
            onPartial(VisualScanResult(exact,coverage.failed,epoch,coverage))
        }
        try {
            for((index,ref) in candidates.withIndex()){
                coroutineContext.ensureActive()
                check(DuplicatePrivacyGuard.generation(context)==epoch){"Поиск сброшен после изменения приватности"}
                val outcome=try{
                    val ids=metrics.measure("identity"){PublicMediaIdentity.capture(context,requireNotNull(ref.item.savedUri))}
                    check(DuplicatePrivacyGuard.accepts(context,epoch,ref.key,ids))
                    if(ref.appDownloadId!=null)check(DuplicatePrivacyGuard.accepts(context,epoch,ref.appDownloadId,ids))
                    if(ids.any{it in seen}) coverage.record(VisualScanOutcome.ALIAS)
                    else {
                        val (fingerprint,cached)=read(context,ref,metrics,epoch)
                        rows+=fingerprint;seen+=ids
                        coverage.record(if(cached)VisualScanOutcome.CACHED else VisualScanOutcome.COMPUTED,
                            fingerprint.frames.size!=(if(fingerprint.type==MediaType.PHOTO)1 else 5))
                    }
                }catch(e:CancellationException){throw e}catch(_:Exception){coverage.record(VisualScanOutcome.FAILED)}
                coverage=outcome
                progress(coverage)
                if((index+1)%100==0){
                    publishChecked();metrics.report("visual",index+1,coverage.failed)
                }
            }
            onPhase(DuplicateScanPhase.GROUPING)
            val ctx=coroutineContext
            val groups=metrics.measure("grouping"){VisualDuplicatePolicy.groups(rows){ctx.ensureActive();check(DuplicatePrivacyGuard.generation(context)==epoch)}}
            check(DuplicatePrivacyGuard.generation(context)==epoch)
            metrics.report("completed",candidates.size,coverage.failed)
            com.domenota.medialoader.core.logging.AppLog.i("DuplicateScanMetrics", "gallery coverage=${coverage.total} cached=${coverage.cached} computed=${coverage.computed} aliases=${coverage.aliases} failed=${coverage.failed} incompleteVisual=${coverage.incompleteVisual} scope=${coverage.source}")
            VisualScanResult(groups,coverage.failed,epoch,coverage)
        } catch(e:CancellationException) {
            // Capture the final checked batch too, including scans stopped before 100 files.
            // The callback writes synchronously under the privacy guard; no cancelled IO launch.
            publishChecked()
            throw e
        }
    }
}

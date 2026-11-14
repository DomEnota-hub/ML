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

internal data class VisualScanResult(val groups:List<VisualDuplicateGroup>,val skipped:Int)
internal object VisualDuplicateScanner {
    private data class Stamp(val bytes:Long,val modified:Long?)
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
    private suspend fun read(context:Context,ref:LibraryMediaRef):VisualFingerprint {
        val row=ref.item;val uri=Uri.parse(requireNotNull(row.savedUri));val before=stamp(context,uri)
        val hash=sha(context,uri)
        var width=0;var height=0;var duration:Long?=null;val frames=mutableListOf<Frame>()
        try {
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
        }catch(e:CancellationException){throw e}catch(_:Exception){frames.clear()}
        check(before==stamp(context,uri)){"File changed during scan"}
        return VisualFingerprint(ref.key,uri.toString(),row.mediaType,before.bytes,before.modified,hash,width,height,duration,
            frames.map{it.hash},frames.isNotEmpty()&&frames.all{it.informative})
    }
    suspend fun scan(context:Context,refs:List<LibraryMediaRef>,progress:(Int,Int)->Unit={ _, _ -> }):VisualScanResult=withContext(Dispatchers.IO){
        val candidates=refs.filter{val r=it.item;!r.isPrivate&&!r.hidden&&r.state==DownloadState.COMPLETED&&r.mediaType!=MediaType.AUDIO&&!r.savedUri.isNullOrBlank()}
            .distinctBy{it.item.savedUri}
        val rows=mutableListOf<VisualFingerprint>();var skipped=0
        for((index,ref) in candidates.withIndex()){
            coroutineContext.ensureActive()
            try{rows+=read(context,ref)}catch(e:CancellationException){throw e}catch(_:Exception){skipped++}
            progress(index+1,candidates.size)
        }
        val ctx=coroutineContext
        VisualScanResult(VisualDuplicatePolicy.groups(rows){ctx.ensureActive()},skipped)
    }
}

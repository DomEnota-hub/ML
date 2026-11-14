package com.domenota.medialoader.data.editor

import android.content.Context
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

enum class BatchPhotoFormat(val extension:String,val mime:String) {
    JPEG("jpg","image/jpeg"), PNG("png","image/png"), WEBP("webp","image/webp")
}
data class BatchPhotoSpec(
    val maxSide:Int=1920, val format:BatchPhotoFormat=BatchPhotoFormat.JPEG,
    val quality:Int=85, val removeGps:Boolean=true, val removeExif:Boolean=false,
    val effect:PhotoEffect=PhotoEffect.NONE, val watermark:String=""
) {
    init { require(maxSide in 0..8192 && quality in 1..100 && watermark.length<=100) }
    fun dimensions(w:Int,h:Int):Pair<Int,Int> {
        require(w>0 && h>0)
        val scale=if(maxSide==0) 1.0 else minOf(1.0,maxSide.toDouble()/maxOf(w,h))
        return (w*scale).roundToInt().coerceAtLeast(1) to (h*scale).roundToInt().coerceAtLeast(1)
    }
}
data class BatchPhotoOutcome(val id:String,val name:String,val error:String?=null)
data class BatchPhotoState(val running:Boolean=false,val total:Int=0,
    val outcomes:List<BatchPhotoOutcome> = emptyList(),val cancelled:Boolean=false) {
    val failed get()=outcomes.filter{it.error!=null}
}

class BatchPhotoEditor(private val context:Context) {
    suspend fun export(uri:Uri,spec:BatchPhotoSpec):PhotoExportResult=withContext(Dispatchers.IO) {
        val resolver=context.contentResolver
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        resolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,bounds)}
        val (w,h)=spec.dimensions(bounds.outWidth,bounds.outHeight)
        var sample=1
        while(bounds.outWidth/(sample*2)>=w && bounds.outHeight/(sample*2)>=h) sample*=2
        // Reject excessive full-resolution jobs rather than exhausting the process heap.
        require(bounds.outWidth.toLong()/sample*(bounds.outHeight/sample)<=24_000_000L){"Изображение слишком большое. Уменьшите разрешение."}
        val exif=resolver.openInputStream(uri)?.use{runCatching{ExifInterface(it)}.getOrNull()}
        val original=resolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,
            BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888})}
            ?:throw IOException("Не удалось открыть фото")
        val owned=mutableListOf(original)
        val output=File.createTempFile("batch-photo-", ".${spec.format.extension}",context.cacheDir)
        try {
            val orientation=exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION,1)?:1
            val matrix=Matrix().apply {
                when(orientation){
                    2->setScale(-1f,1f);3->setRotate(180f);4->setScale(1f,-1f)
                    5->{setRotate(90f);postScale(-1f,1f)}
                    6->setRotate(90f);7->{setRotate(-90f);postScale(-1f,1f)}
                    8->setRotate(-90f)
                }
            }
            var bitmap=if(matrix.isIdentity)original else Bitmap.createBitmap(original,0,0,original.width,original.height,matrix,true).also{owned.add(it)}
            val target=spec.dimensions(if(orientation in 5..8)bounds.outHeight else bounds.outWidth,if(orientation in 5..8)bounds.outWidth else bounds.outHeight)
            if(bitmap.width!=target.first || bitmap.height!=target.second)
                bitmap=Bitmap.createScaledBitmap(bitmap,target.first,target.second,true).also{owned.add(it)}
            currentCoroutineContext().ensureActive()
            if(spec.effect!=PhotoEffect.NONE) bitmap=PhotoColorMath.apply(bitmap,PhotoColorAdjustments(effect=spec.effect)).also{owned.add(it)}
            if(spec.watermark.isNotBlank() || spec.format==BatchPhotoFormat.JPEG){
                val copy=Bitmap.createBitmap(bitmap.width,bitmap.height,Bitmap.Config.ARGB_8888).also{owned.add(it)}
                val canvas=Canvas(copy)
                if(spec.format==BatchPhotoFormat.JPEG)canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(bitmap,0f,0f,null)
                if(spec.watermark.isNotBlank()) TextLayerRenderer.draw(context,canvas,copy.width,copy.height,
                    listOf(TextLayer(text=spec.watermark,centerX=.5f,centerY=.9f,size=.045f,opacity=.8f)),includeAll=true)
                bitmap=copy
            }
            currentCoroutineContext().ensureActive()
            val format=when(spec.format){
                BatchPhotoFormat.JPEG->Bitmap.CompressFormat.JPEG
                BatchPhotoFormat.PNG->Bitmap.CompressFormat.PNG
                BatchPhotoFormat.WEBP->if(android.os.Build.VERSION.SDK_INT>=30)Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            }
            output.outputStream().use{if(!bitmap.compress(format,spec.quality,it))throw IOException("Ошибка кодирования")}
            if(spec.format==BatchPhotoFormat.JPEG && !spec.removeExif && exif!=null){
                val dest=ExifInterface(output.absolutePath)
                val tags=listOf(ExifInterface.TAG_MAKE,ExifInterface.TAG_MODEL,ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_DATETIME_ORIGINAL,ExifInterface.TAG_EXPOSURE_TIME,ExifInterface.TAG_F_NUMBER,
                    ExifInterface.TAG_FOCAL_LENGTH,ExifInterface.TAG_ISO_SPEED_RATINGS,ExifInterface.TAG_WHITE_BALANCE)
                tags.forEach{tag->exif.getAttribute(tag)?.let{dest.setAttribute(tag,it)}}
                if(!spec.removeGps) listOf(ExifInterface.TAG_GPS_LATITUDE,ExifInterface.TAG_GPS_LATITUDE_REF,
                    ExifInterface.TAG_GPS_LONGITUDE,ExifInterface.TAG_GPS_LONGITUDE_REF,
                    ExifInterface.TAG_GPS_ALTITUDE,ExifInterface.TAG_GPS_ALTITUDE_REF,
                    ExifInterface.TAG_GPS_DATESTAMP,ExifInterface.TAG_GPS_TIMESTAMP).forEach{tag->exif.getAttribute(tag)?.let{dest.setAttribute(tag,it)}}
                dest.setAttribute(ExifInterface.TAG_ORIENTATION,"1")
                dest.saveAttributes()
            }
            currentCoroutineContext().ensureActive()
            if(output.length()==0L)throw IOException("Пустой результат")
            PhotoExportResult(output,spec.format.extension,spec.format.mime)
        }catch(t:Throwable){output.delete();throw t}
        finally{owned.distinct().forEach{if(!it.isRecycled)it.recycle()}}
    }
}

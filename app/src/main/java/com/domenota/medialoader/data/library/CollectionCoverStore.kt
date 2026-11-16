package com.domenota.medialoader.data.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.util.AtomicFile
import com.domenota.medialoader.ui.screens.organization.CollectionCrop
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class CollectionCover(val file:File,val source:String,val crop:CollectionCrop)
internal sealed interface CollectionCoverSelection {
    data object Loading:CollectionCoverSelection
    data object Automatic:CollectionCoverSelection
    data class Selected(val cover:CollectionCover):CollectionCoverSelection
    data class Unavailable(val source:String?=null,val crop:CollectionCrop?=null):CollectionCoverSelection
}
internal enum class CollectionCoverPresentation { PLACEHOLDER, CUSTOM, AUTOMATIC }
internal fun collectionCoverPresentation(selection:CollectionCoverSelection,customAllowed:Boolean,automaticAllowed:Boolean):CollectionCoverPresentation = when(selection){
    is CollectionCoverSelection.Selected -> if(customAllowed)CollectionCoverPresentation.CUSTOM else CollectionCoverPresentation.PLACEHOLDER
    CollectionCoverSelection.Automatic -> if(automaticAllowed)CollectionCoverPresentation.AUTOMATIC else CollectionCoverPresentation.PLACEHOLDER
    else -> CollectionCoverPresentation.PLACEHOLDER
}
/** Covers and source locator metadata never enter MediaStore, exports, or the public index. */
internal class CollectionCoverStore(context:Context) {
    private val root=File(context.applicationContext.filesDir,"collection-covers")
    private val resolver=context.applicationContext.contentResolver
    private fun directory(id:String):File {
        require(id.matches(Regex("[A-Za-z0-9_-]+")))
        return File(root,id)
    }
    private fun cacheKey(id:String)="${root.absolutePath}/$id"
    /** Tiny metadata-only cache; never hold a bitmap or do file IO on the UI thread. */
    fun cachedSelection(id:String):CollectionCoverSelection?=synchronized(cache){cache[cacheKey(id)]}
    private fun rememberSelection(id:String,selection:CollectionCoverSelection){synchronized(cache){cache[cacheKey(id)]=selection}}
    private fun diskSelection(id:String):CollectionCoverSelection {
        val dir=directory(id)
        val manifest=File(dir,"cover.json")
        if(!manifest.exists()&&!File(dir,"cover.json.bak").exists()&&!File(dir,"cover.json.new").exists())
            return if(dir.listFiles().orEmpty().any{it.name.endsWith(".jpg")})CollectionCoverSelection.Unavailable() else CollectionCoverSelection.Automatic
        return try {
            val json=AtomicFile(manifest).openRead().use{JSONObject(it.bufferedReader().readText())}
            val name=json.getString("file")
            val source=json.getString("source")
            val crop=CollectionCrop(json.getDouble("x").toFloat(),json.getDouble("y").toFloat(),json.getDouble("zoom").toFloat())
            require(name.matches(Regex("[A-Za-z0-9-]+\\.jpg")))
            val file=File(dir,name)
            if(file.isFile && file.length()>0)CollectionCoverSelection.Selected(CollectionCover(file,source,crop))
            else CollectionCoverSelection.Unavailable(source,crop)
        }catch(_:Exception){CollectionCoverSelection.Unavailable()}
    }
    /** Called on IO; serialized with atomic writes/deletion, so an older read cannot
     * replace a freshly committed selection in the memory cache. */
    fun readSelection(id:String):CollectionCoverSelection=synchronized(diskLock){diskSelection(id).also{rememberSelection(id,it)}}
    fun read(id:String):CollectionCover?=(readSelection(id) as? CollectionCoverSelection.Selected)?.cover
    fun decode(source:String):Bitmap {
        val uri=Uri.parse(source)
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        resolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,bounds)}
        require(bounds.outWidth>0 && bounds.outHeight>0){"Изображение недоступно"}
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>2048)sample*=2
        val sourceBitmap=resolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=sample})}
            ?:error("Не удалось открыть изображение")
        val orientation=resolver.openInputStream(uri)?.use{runCatching{ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,1)}.getOrDefault(1)}?:1
        val matrix=Matrix().apply{when(orientation){
            2->setScale(-1f,1f);3->setRotate(180f);4->setScale(1f,-1f)
            5->{setRotate(90f);postScale(-1f,1f)};6->setRotate(90f)
            7->{setRotate(-90f);postScale(-1f,1f)};8->setRotate(-90f)
        }}
        if(matrix.isIdentity)return sourceBitmap
        return Bitmap.createBitmap(sourceBitmap,0,0,sourceBitmap.width,sourceBitmap.height,matrix,true).also{if(it!==sourceBitmap)sourceBitmap.recycle()}
    }
    /** Prepare first; only a successful metadata rename makes the new image visible. */
    suspend fun save(id:String,source:String,bitmap:Bitmap,crop:CollectionCrop) {
        val job=currentCoroutineContext()
        synchronized(diskLock){
        val dir=directory(id).apply{check(mkdirs()||isDirectory)}
        val old=(diskSelection(id) as? CollectionCoverSelection.Selected)?.cover
        val file=File(dir,"${UUID.randomUUID()}.jpg")
        val bounded=crop.bounded(bitmap.width,bitmap.height)
        val rect=bounded.rect(bitmap.width,bitmap.height)
        val targetWidth=minOf(800,rect.width.toInt().coerceAtLeast(1), (rect.height*.8f).toInt().coerceAtLeast(1))
        val targetHeight=(targetWidth/.8f).toInt().coerceAtLeast(1)
        val result=Bitmap.createBitmap(targetWidth,targetHeight,Bitmap.Config.ARGB_8888)
        val atomic=AtomicFile(File(dir,"cover.json"))
        try {
            Canvas(result).drawBitmap(bitmap,android.graphics.Rect(rect.left.toInt(),rect.top.toInt(),(rect.left+rect.width).toInt(),(rect.top+rect.height).toInt()),
                RectF(0f,0f,targetWidth.toFloat(),targetHeight.toFloat()),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            file.outputStream().use{check(result.compress(Bitmap.CompressFormat.JPEG,88,it)){"Не удалось сохранить обложку"};it.fd.sync()}
            job.ensureActive()
            val json=JSONObject().put("file",file.name).put("source",source).put("x",bounded.centerX).put("y",bounded.centerY).put("zoom",bounded.zoom)
            val out=atomic.startWrite()
            try{out.write(json.toString().toByteArray());job.ensureActive();atomic.finishWrite(out)}catch(e:Exception){atomic.failWrite(out);throw e}
            rememberSelection(id,CollectionCoverSelection.Selected(CollectionCover(file,source,bounded)))
            old?.file?.takeIf{it!=file}?.delete()
            revision.value++
        } catch(e:Exception){file.delete();throw e} finally{result.recycle()}
        }
    }
    fun automatic(id:String)=synchronized(diskLock){
        check(directory(id).deleteRecursively()){ "Не удалось удалить выбранную обложку" }
        rememberSelection(id,CollectionCoverSelection.Automatic)
        revision.value++
    }
    companion object {
        private val diskLock=Any()
        private val cache=object:LinkedHashMap<String,CollectionCoverSelection>(128,.75f,true){
            override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,CollectionCoverSelection>?):Boolean=size>128
        }
        private val revision=MutableStateFlow(0L)
        val changes=revision.asStateFlow()
    }
}

package com.domenota.medialoader.data.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.AtomicFile
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.editor.LocalTextOcr
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

internal enum class SmartAlbum(val title: String) {
    DOCUMENTS("Документы"), PORTRAITS("Портреты"), SELFIES("Селфи"), HIGH_RESOLUTION("Высокое разрешение");
    val key get() = "smart:" + name
}
internal data class SmartPhotoFacts(val width: Int, val height: Int, val faces: Int, val maxFaceArea: Float,
    val frontHint: Boolean, val words: Int, val lines: Int, val confidence: Int, val paleFraction: Float)
internal object SmartAlbumPolicy {
    fun categories(facts: SmartPhotoFacts, threshold: Int): Set<SmartAlbum> = buildSet {
        if (facts.width.toLong()*facts.height >= threshold.toLong()*1_000_000L) add(SmartAlbum.HIGH_RESOLUTION)
        if (facts.faces in 1..4 && facts.maxFaceArea >= .045f) add(SmartAlbum.PORTRAITS)
        if (facts.faces > 0 && facts.frontHint) add(SmartAlbum.SELFIES)
        if (facts.confidence >= 45 && facts.words >= 8 && facts.lines >= 3 &&
            (facts.paleFraction >= .30f || facts.words >= 24 && facts.lines >= 5) &&
            facts.maxFaceArea < .12f) add(SmartAlbum.DOCUMENTS)
    }
    fun eligible(row: DownloadEntity) = !row.hidden && !row.isPrivate && row.state == DownloadState.COMPLETED &&
        row.mediaType == MediaType.PHOTO && !row.savedUri.isNullOrBlank()
    fun frontHint(name: String, lens: String?, comment: String?): Boolean {
        val evidence = listOf(name,lens.orEmpty(),comment.orEmpty()).joinToString(" ").lowercase(Locale.ROOT)
        return Regex("""\bselfie\b|\bfront[ _-]?(camera|lens)\b|селфи""").containsMatchIn(evidence.replace('_',' '))
    }
}
internal data class SmartAlbumSettings(val enabled: Set<SmartAlbum> = SmartAlbum.entries.toSet(), val threshold: Int = 24)
internal class SmartAlbumSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("smart_album_settings",Context.MODE_PRIVATE)
    fun read() = SmartAlbumSettings(
        prefs.getStringSet("enabled",SmartAlbum.entries.map { it.name }.toSet()).orEmpty().mapNotNull { key -> SmartAlbum.entries.firstOrNull { it.name==key } }.toSet(),
        prefs.getInt("mp",24).coerceIn(8,108))
    fun save(value: SmartAlbumSettings) { prefs.edit().putStringSet("enabled",value.enabled.map { it.name }.toSet()).putInt("mp",value.threshold).apply() }
}
internal data class SmartIndexStatus(val members: Map<SmartAlbum,Set<String>> = emptyMap(),val running:Boolean=false,
    val checked:Int=0,val total:Int=0,val failed:Int=0,val error:String?=null)

/** One low-priority process-owned scanner. Cache contains only derived facts, never faces or OCR text. */
internal object SmartAlbumIndex {
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val states = MutableStateFlow<Map<String,SmartIndexStatus>>(emptyMap())
    val state = states.asStateFlow()
    private var task: Job? = null
    private var activeSource: String? = null
    private var activeRows: List<DownloadEntity> = emptyList()
    private var activeSettings: SmartAlbumSettings? = null
    private var generation = 0L
    @Synchronized private fun publish(source: String, version: Long, status: SmartIndexStatus) {
        if (generation==version) states.value=states.value+(source to status)
    }
    @Synchronized fun request(context: Context, source: String, rows: List<DownloadEntity>, settings: SmartAlbumSettings, force:Boolean=false) {
        val public = rows.filter(SmartAlbumPolicy::eligible).distinctBy { it.id }
        if (!force && activeSource==source && activeRows==public && activeSettings==settings &&
            (task?.isActive == true || states.value[source]?.error==null && states.value.containsKey(source))) return
        val oldSource=activeSource
        task?.cancel()
        if (oldSource!=null) states.value[oldSource]?.let { states.value=states.value+(oldSource to it.copy(running=false)) }
        activeSource=source; activeRows=public; activeSettings=settings; val version=++generation
        if (settings.enabled.isEmpty()) { publish(source,version,SmartIndexStatus()); return }
        publish(source,version,SmartIndexStatus(running=true,total=public.size))
        val app=context.applicationContext
        task=scope.launch {
            mutex.lock()
            try { scan(app,source,version,public,settings) }
            catch (e:CancellationException) { throw e }
            catch (e:Exception) { publish(source,version,SmartIndexStatus(total=public.size,error="Не удалось завершить анализ")) }
            finally { mutex.unlock() }
        }
    }
    private data class Stamp(val size:Long,val modified:Long)
    private fun stamp(context:Context,uri:Uri): Stamp? = runCatching {
        if(uri.scheme=="file") {
            val f=File(requireNotNull(uri.path)); Stamp(f.length(),f.lastModified())
        } else context.contentResolver.query(uri,arrayOf("_size","date_modified"),null,null,null)?.use { c ->
            if(c.moveToFirst() && !c.isNull(0) && !c.isNull(1)) Stamp(c.getLong(0),c.getLong(1)*1000) else null
        }
    }.getOrNull()?.takeIf { it.size>0 && it.modified>0 }
    private fun cache(context:Context,uri:String,name:String):AtomicFile {
        val directory=File(context.noBackupFilesDir,"smart-album-facts-v1").apply { mkdirs() }
        val key=MessageDigest.getInstance("SHA-256").digest((uri+"\u0000"+name).toByteArray()).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory,"$key.json"))
    }
    private fun cached(file:AtomicFile,stamp:Stamp?):SmartPhotoFacts? = runCatching {
        if(stamp==null) return null
        val j=JSONObject(file.openRead().bufferedReader().use { it.readText() })
        if(j.getLong("size")!=stamp.size || j.getLong("modified")!=stamp.modified) return null
        SmartPhotoFacts(j.getInt("width"),j.getInt("height"),j.getInt("faces"),j.getDouble("area").toFloat(),j.getBoolean("front"),
            j.getInt("words"),j.getInt("lines"),j.getInt("confidence"),j.getDouble("pale").toFloat())
    }.getOrNull()
    private fun save(file:AtomicFile,stamp:Stamp?,facts:SmartPhotoFacts) {
        if(stamp==null) return
        runCatching {
            val j=JSONObject().put("size",stamp.size).put("modified",stamp.modified).put("width",facts.width).put("height",facts.height)
                .put("faces",facts.faces).put("area",facts.maxFaceArea.toDouble()).put("front",facts.frontHint)
                .put("words",facts.words).put("lines",facts.lines).put("confidence",facts.confidence).put("pale",facts.paleFraction.toDouble())
            val s=file.startWrite()
            try { s.write(j.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(s) }
            catch(e:Exception) { file.failWrite(s); throw e }
        }
    }
    private suspend fun scan(context:Context,source:String,version:Long,rows:List<DownloadEntity>,settings:SmartAlbumSettings) {
        val members=SmartAlbum.entries.associateWith { mutableSetOf<String>() }
        var failed=0
        val ai=settings.enabled.any { it!=SmartAlbum.HIGH_RESOLUTION }
        val detector=if(ai) FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).setMinFaceSize(.1f).build()) else null
        try {
            rows.forEachIndexed { index,row ->
                currentCoroutineContext().ensureActive()
                try {
                    val uri=Uri.parse(requireNotNull(row.savedUri)); val before=stamp(context,uri); val file=cache(context,uri.toString(),row.originalName)
                    val saved=cached(file,before)
                    val facts=saved ?: analyze(context,uri,row.originalName,detector)
                    currentCoroutineContext().ensureActive()
                    if(before!=null && stamp(context,uri)!=before) error("Файл изменился")
                    // Do not cache metadata-only facts as if AI had already examined the photo.
                    if(saved==null && ai) save(file,before,facts)
                    SmartAlbumPolicy.categories(facts,settings.threshold).filter { it in settings.enabled }.forEach { members.getValue(it).add(row.id) }
                } catch(e:CancellationException) { throw e } catch(_:Exception) { failed++ }
                if(index%12==0 || index==rows.lastIndex) publish(source,version,SmartIndexStatus(members.mapValues { it.value.toSet() },true,index+1,rows.size,failed))
                if(ai && index%4==0) delay(24)
            }
            publish(source,version,SmartIndexStatus(members.mapValues { it.value.toSet() },false,rows.size,rows.size,failed))
        } finally { detector?.close() }
    }
    private suspend fun analyze(context:Context,uri:Uri,name:String,detector:com.google.mlkit.vision.face.FaceDetector?):SmartPhotoFacts {
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,bounds) }
        check(bounds.outWidth>0 && bounds.outHeight>0) { "Размер недоступен" }
        val width=bounds.outWidth; val height=bounds.outHeight
        if(detector==null) return SmartPhotoFacts(width,height,0,0f,false,0,0,0,0f)
        var sample=1
        while(maxOf(width,height)/sample>1280) sample*=2
        val options=BitmapFactory.Options().apply { inSampleSize=sample; inPreferredConfig=Bitmap.Config.ARGB_8888 }
        val bitmap=context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,options) } ?: error("Изображение недоступно")
        var normalized=bitmap
        try {
            val exif=runCatching { context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) } }.getOrNull()
            val rotation=when(exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if(rotation!=0f) normalized=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,Matrix().apply { postRotate(rotation) },true)
            val faces=Tasks.await(detector.process(InputImage.fromBitmap(normalized,0)))
            val area=faces.maxOfOrNull { f -> f.boundingBox.width().toFloat()*f.boundingBox.height()/(normalized.width.toFloat()*normalized.height) } ?: 0f
            val hint=SmartAlbumPolicy.frontHint(name,exif?.getAttribute("LensModel"),exif?.getAttribute("UserComment"))
            var pale=0; var samples=0
            for(y in 0 until normalized.height step maxOf(1,normalized.height/24))
                for(x in 0 until normalized.width step maxOf(1,normalized.width/24)) {
                    val pixel=normalized.getPixel(x,y); val r=Color.red(pixel); val g=Color.green(pixel); val b=Color.blue(pixel)
                    if(minOf(r,g,b)>160 && maxOf(r,g,b)-minOf(r,g,b)<48) pale++
                    samples++
                }
            val paleRatio=pale.toFloat()/samples.coerceAtLeast(1)
            // Avoid expensive multi-pass editor OCR for every library photograph.
            val ocr=if(area<.12f) LocalTextOcr.readPageQuick(context,normalized) else null
            val content=ocr?.text.orEmpty()
            val words=Regex("""[\p{L}\p{N}]{2,}""").findAll(content).count()
            val lines=content.lineSequence().count { it.trim().length>=4 }
            return SmartPhotoFacts(width,height,faces.size,area,hint,words,lines,ocr?.confidence ?: 0,paleRatio)
        } finally { if(normalized!==bitmap) normalized.recycle(); bitmap.recycle() }
    }
}

package com.domenota.medialoader.data.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.AtomicFile
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.data.editor.LocalTextOcr
import com.domenota.medialoader.data.playback.BackgroundMediaWork
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class SmartCategory(val title: String, val key: String) {
    DOCUMENTS("Документы","smart:documents"),
    PORTRAITS("Портреты","smart:portraits"),
    SELFIES("Селфи","smart:selfies"),
    HIGH_RES("Высокое разрешение","smart:high-res");
}
internal data class SmartPhoto(val uri: String, val bytes: Long, val modified: Long, val width: Int, val height: Int,
    val document: Boolean, val portrait: Boolean, val selfie: Boolean, val analysisComplete: Boolean = true) {
    fun matches(category: SmartCategory, threshold: Int): Boolean = when(category) {
        SmartCategory.DOCUMENTS -> document
        SmartCategory.PORTRAITS -> portrait
        SmartCategory.SELFIES -> selfie
        SmartCategory.HIGH_RES -> SmartAlbumPolicy.highResolution(width,height,threshold)
    }
}
internal data class SmartAlbumStatus(val photos: Map<String,SmartPhoto> = emptyMap(), val done: Int = 0,
    val total: Int = 0, val running: Boolean = false, val skipped: Int = 0)

internal object SmartAlbumPolicy {
    /** JNI may be missing on an unsupported ABI or its initialization may fail.
     * Contain that recoverable capability failure without swallowing cancellation. */
    suspend fun <T> optionalRecognition(block: suspend () -> T): T? = try { block() }
        catch (e: CancellationException) { throw e }
        catch (_: LinkageError) { null }
        catch (_: Exception) { null }

    fun publicPhoto(row: DownloadEntity) = !row.hidden && !row.isPrivate && row.state == DownloadState.COMPLETED &&
        row.mediaType == MediaType.PHOTO && !row.savedUri.isNullOrBlank()
    fun highResolution(width: Int, height: Int, threshold: Int): Boolean =
        width > 0 && height > 0 && width.toLong()*height >= threshold.coerceIn(8,64)*1_000_000L
    fun document(text: String, confidence: Int, pageLike: Boolean): Boolean {
        val lines = text.lineSequence().map(String::trim).filter { line -> line.count { it.isLetterOrDigit() } >= 5 }.toList()
        val letters = lines.sumOf { it.count(Char::isLetterOrDigit) }
        return confidence >= 55 && lines.size >= 3 && letters >= 45 &&
            (pageLike || (lines.size >= 7 && letters >= 125 && confidence >= 68))
    }
    fun portrait(largestFaceFraction: Float, faceCount: Int) = faceCount > 0 && largestFaceFraction >= .075f
    fun selfie(frontHint: Boolean, largestFaceFraction: Float, faceCount: Int, centered: Boolean) =
        faceCount > 0 && (frontHint || (faceCount <= 3 && largestFaceFraction >= .26f && centered))
}

/** Process-wide, bounded, incremental indexing; cache contains flags and file stamps, not faces or OCR text. */
internal object SmartAlbumIndex {
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val gate = Mutex()
    private val status = MutableStateFlow<Map<String,SmartAlbumStatus>>(emptyMap())
    val state = status.asStateFlow()
    private val jobs = mutableMapOf<String,Job>()
    private val inputs = mutableMapOf<String,List<DownloadEntity>>()
    private fun publish(source: String,value: SmartAlbumStatus) {
        synchronized(status) { status.value = status.value + (source to value) }
    }
    fun refresh(context: Context, source: String, rows: List<DownloadEntity>, force: Boolean = false) {
        val public = rows.filter(SmartAlbumPolicy::publicPhoto).distinctBy { it.id }
        if (!force && inputs[source] == public) return
        val old = inputs[source].orEmpty()
        inputs[source] = public
        jobs[source]?.cancel()
        val app = context.applicationContext
        val byId = public.associateBy { it.id }
        val previous = status.value[source]?.photos.orEmpty().filter { (id,photo) -> byId[id]?.savedUri == photo.uri }
        val activeUris = inputs.values.flatten().mapNotNull { it.savedUri }.toSet()
        val removedUris = old.mapNotNull { it.savedUri }.filterNot(activeUris::contains)
        publish(source,SmartAlbumStatus(previous,0,public.size,true))
        jobs[source] = scope.launch {
            gate.withLock {
                removedUris.forEach { runCatching { cacheFile(app,it).delete() } }
                val photos = linkedMapOf<String,SmartPhoto>()
                val analyzed = mutableMapOf<String,SmartPhoto?>()
                var skipped = 0
                val detector = runCatching { FaceDetection.getClient(FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).setMinFaceSize(.12f).build()) }.getOrNull()
                try {
                    public.forEachIndexed { i,row ->
                        ensureActive()
                        val uri = Uri.parse(requireNotNull(row.savedUri))
                        val stamp = runCatching { stamp(app,uri) }.getOrNull()
                        val cached = if (force) null else stamp?.let { load(app,uri.toString(),it.first,it.second) }
                        val key = uri.toString()
                        val photo = cached ?: if (analyzed.containsKey(key)) analyzed[key] else SmartAlbumPolicy.optionalRecognition {
                            BackgroundMediaWork.awaitIdle()
                            analyze(app,uri,stamp,detector)
                        }.also { analyzed[key] = it }
                        ensureActive()
                        if (photo == null) skipped++ else {
                            photos[row.id] = photo
                            if (!photo.analysisComplete) skipped++
                            if (cached == null && photo.analysisComplete && photo.modified > 0 && photo.bytes > 0) save(app,photo)
                        }
                        if ((i+1)%8 == 0 || i+1 == public.size)
                            publish(source,SmartAlbumStatus(photos.toMap(),i+1,public.size,true,skipped))
                        if (cached == null) delay(15)
                    }
                    publish(source,SmartAlbumStatus(photos.toMap(),public.size,public.size,false,skipped))
                } finally { runCatching { detector?.close() } }
            }
        }
    }
    fun stop(source: String) {
        jobs.remove(source)?.cancel(); inputs.remove(source)
        val old = status.value[source] ?: return
        publish(source,old.copy(running = false))
    }
    private fun stamp(context: Context,uri: Uri): Pair<Long,Long>? {
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path))
            return if (file.isFile) file.length() to file.lastModified() else null
        }
        if (uri.scheme != "content") return null
        return context.contentResolver.query(uri,arrayOf("_size","date_modified"),null,null,null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0) && !c.isNull(1)) c.getLong(0) to c.getLong(1)*1000 else null
        }
    }
    private fun cacheFile(context: Context,uri: String): AtomicFile {
        val dir = File(context.noBackupFilesDir,"smart-albums-v1")
        if (!dir.isDirectory) dir.mkdirs()
        val name = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(dir,name))
    }
    private fun load(context: Context,uri: String,bytes: Long,modified: Long): SmartPhoto? = runCatching {
        if (bytes <= 0 || modified <= 0) return null
        val j = JSONObject(cacheFile(context,uri).openRead().bufferedReader().use { it.readText() })
        if (j.getLong("bytes") != bytes || j.getLong("modified") != modified || j.getString("uri") != uri) return null
        SmartPhoto(uri,bytes,modified,j.getInt("w"),j.getInt("h"),j.getBoolean("doc"),j.getBoolean("portrait"),j.getBoolean("selfie"))
    }.getOrNull()
    private fun save(context: Context,photo: SmartPhoto) {
        runCatching {
            val file = cacheFile(context,photo.uri); val stream = file.startWrite()
            try {
                val j = JSONObject().put("uri",photo.uri).put("bytes",photo.bytes).put("modified",photo.modified)
                    .put("w",photo.width).put("h",photo.height).put("doc",photo.document).put("portrait",photo.portrait).put("selfie",photo.selfie)
                stream.write(j.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream)
            } catch (e: Exception) { file.failWrite(stream); throw e }
        }
    }
    private suspend fun analyze(context: Context,uri: Uri,snapshotStamp: Pair<Long,Long>?,
        detector: com.google.mlkit.vision.face.FaceDetector?): SmartPhoto? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,bounds); Unit } ?: return null
        val w = bounds.outWidth; val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (maxOf(w,h)/sample > 1600) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val original = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,opts) } ?: return null
        val exif = runCatching { resolver.openInputStream(uri)?.use { ExifInterface(it) } }.getOrNull()
        val rotation = when(exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION,1)) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90f
            ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180f
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270f
            else -> 0f
        }
        val image = if (rotation != 0f) Bitmap.createBitmap(original,0,0,original.width,original.height,Matrix().apply { postRotate(rotation) },true) else original
        try {
            currentCoroutineContext().ensureActive()
            val hint = listOf("LensModel","UserComment","ImageDescription").mapNotNull { exif?.getAttribute(it) }.joinToString(" ").lowercase()
            val front = listOf("front camera","front-facing","front lens","selfie").any(hint::contains)
            var count = 0; var fraction = 0f; var centered = false
            // Face detection only: never identify or retain a person's face.
            val faces = runCatching { detector?.let { Tasks.await(it.process(InputImage.fromBitmap(image,0))) } }.getOrNull()
            if (faces != null) {
                count = faces.size
                faces.maxByOrNull { it.boundingBox.width().toLong()*it.boundingBox.height() }?.boundingBox?.let { box ->
                    fraction = (box.width().toLong()*box.height()).toFloat()/(image.width.toLong()*image.height)
                    centered = kotlin.math.abs(box.exactCenterX()-image.width/2f) <= image.width*.25f
                }
            } else {
                // Platform fallback for devices on which the bundled detector is unavailable.
                runCatching {
                    val width = (image.width.coerceAtLeast(2)/2)*2
                    val height = image.height
                    val resized = Bitmap.createScaledBitmap(image,width,height,true)
                    val rgb = resized.copy(Bitmap.Config.RGB_565,false)
                    try {
                        val found = arrayOfNulls<android.media.FaceDetector.Face>(10)
                        count = android.media.FaceDetector(width,height,found.size).findFaces(rgb,found)
                        found.filterNotNull().maxByOrNull { it.eyesDistance() }?.let { face ->
                            val mid = android.graphics.PointF(); face.getMidPoint(mid)
                            val faceSize = face.eyesDistance()*2.4f
                            fraction = faceSize*faceSize/(width.toLong()*height).toFloat()
                            centered = kotlin.math.abs(mid.x-width/2f) <= width*.25f
                        }
                    } finally { rgb.recycle(); if (resized !== image) resized.recycle() }
                }
            }
            currentCoroutineContext().ensureActive()
            val page = pageLike(image)
            // One bounded OCR pass, only on plausible page-like photos or images without a prominent face.
            val doc = if (page || fraction < .075f) {
                SmartAlbumPolicy.optionalRecognition {
                    val read = LocalTextOcr.readForCatalog(context,image)
                    SmartAlbumPolicy.document(read.text,read.confidence,page)
                }
            } else false
            if (snapshotStamp != null && stamp(context,uri) != snapshotStamp) return null
            return SmartPhoto(uri.toString(),snapshotStamp?.first ?: 0,snapshotStamp?.second ?: 0,w,h,doc == true,
                SmartAlbumPolicy.portrait(fraction,count),SmartAlbumPolicy.selfie(front,fraction,count,centered),
                analysisComplete = doc != null)
        } finally { if (image !== original) image.recycle(); original.recycle() }
    }
    private fun pageLike(image: Bitmap): Boolean {
        var light = 0; var dark = 0; var samples = 0
        for (y in 0 until image.height step maxOf(1,image.height/40))
            for (x in 0 until image.width step maxOf(1,image.width/40)) {
                val c = image.getPixel(x,y)
                val r = android.graphics.Color.red(c); val g = android.graphics.Color.green(c); val b = android.graphics.Color.blue(c)
                if (minOf(r,g,b) > 165 && maxOf(r,g,b)-minOf(r,g,b) < 70) light++
                if (maxOf(r,g,b) < 95) dark++
                samples++
            }
        return samples > 0 && light.toFloat()/samples >= .28f && dark.toFloat()/samples >= .015f
    }
}

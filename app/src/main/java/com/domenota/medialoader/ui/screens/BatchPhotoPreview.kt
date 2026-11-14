package com.domenota.medialoader.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import com.domenota.medialoader.data.editor.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Screen-owned pixels only: private previews never reach disk or the public thumbnail cache. */
internal class BatchPhotoPreviewCache {
    private val images = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val gate = Semaphore(2)
    suspend fun image(key: String, create: suspend () -> Bitmap): Bitmap = gate.withPermit {
        images.get(key) ?: create().also { currentCoroutineContext().ensureActive(); images.put(key, it) }
    }
    // Evicted bitmaps can still be on screen. Let their last owner release them.
    fun clear() = images.evictAll()
}

/** Include provider/file revision in keys, even when a replacement keeps the same URI and size. */
internal suspend fun batchPhotoSourceRevision(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    if (uri.scheme == "file") return@withContext java.io.File(requireNotNull(uri.path)).lastModified().toString()
    for (column in listOf(android.provider.MediaStore.MediaColumns.DATE_MODIFIED,
        android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)) {
        val revision = runCatching {
            context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(column)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getString(index) else null
            }
        }.getOrNull()
        if (revision != null) return@withContext "$column:$revision"
    }
    "unknown"
}

internal suspend fun decodeBatchPreview(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1280) sample *= 2
    val decoded = requireNotNull(resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    })
    try {
        currentCoroutineContext().ensureActive()
        val orientation = resolver.openInputStream(uri)?.use {
            runCatching { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        } ?: 1
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f); 7 -> { setRotate(-90f); postScale(-1f, 1f) }
                8 -> setRotate(-90f)
            }
        }
        val oriented = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0,
            decoded.width, decoded.height, matrix, true)
        if (oriented !== decoded) decoded.recycle()
        oriented
    } catch (error: Throwable) {
        if (!decoded.isRecycled) decoded.recycle()
        throw error
    }
}

/** Same color pipeline, text renderer, and alpha flattening as the batch export. No encoding. */
internal suspend fun renderBatchPreview(context: Context, original: Bitmap, spec: BatchPhotoSpec,
    limit: Int): Bitmap = withContext(Dispatchers.Default) {
    currentCoroutineContext().ensureActive()
    val (w, h) = spec.copy(maxSide = if (spec.maxSide == 0) limit else minOf(spec.maxSide, limit))
        .dimensions(original.width, original.height)
    val source = if (w == original.width && h == original.height) original else
        Bitmap.createScaledBitmap(original, w, h, true)
    var colored: Bitmap? = null
    var retained: Bitmap? = null
    try {
        colored = PhotoColorMath.apply(source, spec.colorAdjustments())
        currentCoroutineContext().ensureActive()
        if (spec.textLayers().none{it.text.isNotBlank()} && spec.format != BatchPhotoFormat.JPEG) {
            retained = colored
            return@withContext requireNotNull(colored)
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(result).apply {
            if (spec.format == BatchPhotoFormat.JPEG) drawColor(Color.WHITE)
            drawBitmap(requireNotNull(colored), 0f, 0f, null)
            TextLayerRenderer.draw(context, this, w, h,
                spec.textLayers(), includeAll = true, fitInside = true)
        }
        result
    } finally {
        if (colored !== original && colored !== source && colored !== retained) colored?.recycle()
        if (source !== original && source !== retained) source.recycle()
    }
}

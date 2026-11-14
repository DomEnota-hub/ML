package com.domenota.medialoader.data.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class PhotoEditSpec(
    val cropLeft: Float = 0f,
    val cropRight: Float = 0f,
    val cropTop: Float = 0f,
    val cropBottom: Float = 0f,
    val rotationDegrees: Int = 0,
    val mirrorHorizontal: Boolean = false,
    val mirrorVertical: Boolean = false,
    val geometry: PhotoGeometry = PhotoGeometry(),
    /** App-owned alpha PNG, never a third-party file path. */
    val inpaintOverlayPath: String? = null,
    val textLayers: List<TextLayer> = emptyList(),
    val colorAdjustments: PhotoColorAdjustments = PhotoColorAdjustments(),
    val drawing: List<PhotoMark> = emptyList(),
    val localEffects: List<PhotoLocalStroke> = emptyList(),
    val graphicWatermark: PhotoGraphicWatermark? = null,
) {
    init {
        require(textLayers.size <= 8)
        require(drawing.size <= PhotoDrawing.MAX_MARKS)
        require(localEffects.size <= PhotoLocalEffects.MAX_STROKES)
        require(cropLeft in 0f..0.95f && cropRight in 0f..0.95f)
        require(cropTop in 0f..0.95f && cropBottom in 0f..0.95f)
        require(cropLeft + cropRight <= 0.95f)
        require(cropTop + cropBottom <= 0.95f)
        require(rotationDegrees % 90 == 0)
    }
}

data class PhotoExportResult(
    val file: File,
    val extension: String,
    val mimeType: String,
)

class PhotoEditor(private val context: Context) {
    suspend fun export(
        source: Uri,
        spec: PhotoEditSpec,
        onProgress: (Int) -> Unit = {},
    ): PhotoExportResult = exportInternal(source, spec, null, onProgress)

    suspend fun exportBatch(source: Uri, batch: BatchPhotoSpec): PhotoExportResult {
        val spec = batch.photoEdit()
        require(spec.inpaintOverlayPath == null) { "AI недоступен в пакетном режиме." }
        return exportInternal(source, spec, batch, {})
    }

    /** One processing pipeline for both modes; only batch encoding, orientation and fitting differ. */
    private suspend fun exportInternal(
        source: Uri, spec: PhotoEditSpec, batch: BatchPhotoSpec?,
        onProgress: (Int) -> Unit,
    ): PhotoExportResult = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        onProgress(5)
        val resolver = context.contentResolver
        val mime = resolver.getType(source).orEmpty()
        val format = batch?.format ?: if (mime.equals("image/png", true))
            BatchPhotoFormat.PNG else BatchPhotoFormat.JPEG
        val output = File(context.cacheDir, "photo-edit-${UUID.randomUUID()}.${format.extension}")
        var owned: Bitmap? = null
        fun image(): Bitmap = requireNotNull(owned)
        fun replace(next: Bitmap) {
            val old = owned
            owned = next
            if (old != null && old !== next && !old.isRecycled) old.recycle()
        }
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
                throw IOException("Не удалось открыть изображение.")
            val exif = if (batch != null) resolver.openInputStream(source)?.use {
                runCatching { android.media.ExifInterface(it) }.getOrNull()
            } else null
            val orientation = exif?.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1) ?: 1
            val (orientedW,orientedH) = PhotoSourceOrientation.dimensions(bounds.outWidth,bounds.outHeight,orientation)
            val target = batch?.dimensions(orientedW,orientedH) ?: (orientedW to orientedH)
            var sample = 1
            while (orientedW/(sample*2) >= target.first && orientedH/(sample*2) >= target.second)
                sample *= 2
            if (batch != null) {
                // Full-resolution requests are never silently downscaled. Reject only
                // unsafe allocations; a failure affects this photo, not the whole batch.
                val pixels = (bounds.outWidth.toLong()/sample) * (bounds.outHeight/sample)
                val runtime = Runtime.getRuntime()
                val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
                val estimated = pixels * 16L
                require(pixels <= 24_000_000L && estimated < available - 12L*1024*1024) {
                    "Недостаточно памяти для обработки исходного разрешения."
                }
            }
            owned = resolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = sample
                    inMutable = spec.inpaintOverlayPath != null
                })
            } ?: throw IOException("Не удалось открыть изображение.")
            if (spec.inpaintOverlayPath != null) {
                val permittedDir = File(context.cacheDir, "inpainting-overlays").canonicalFile
                val overlayFile = File(spec.inpaintOverlayPath).canonicalFile
                if (overlayFile.parentFile != permittedDir || !overlayFile.isFile ||
                    !overlayFile.name.endsWith(".png")) throw IOException("Не удалось прочитать рабочую маску редактора.")
                val overlay = BitmapFactory.decodeFile(overlayFile.absolutePath)
                    ?: throw IOException("Рабочая маска повреждена.")
                try {
                    Canvas(image()).drawBitmap(overlay, Rect(0,0,overlay.width,overlay.height),
                        Rect(0,0,image().width,image().height),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                } finally { overlay.recycle() }
            }
            if (batch != null) {
                replace(PhotoSourceOrientation.apply(image(), orientation))
                if (image().width != target.first || image().height != target.second)
                    replace(Bitmap.createScaledBitmap(image(),target.first,target.second,true))
            }
            currentCoroutineContext().ensureActive()
            onProgress(25)
            val crop = batch?.cropFor(orientedW,orientedH) ?: CropFractions(
                spec.cropLeft,spec.cropRight,spec.cropTop,spec.cropBottom)
            if (crop != CropFractions() || spec.rotationDegrees % 360 != 0 ||
                spec.mirrorHorizontal || spec.mirrorVertical || !spec.geometry.isNeutral()) {
                replace(PhotoGeometryMath.preview(image(),crop,spec.rotationDegrees,
                    spec.mirrorHorizontal,spec.mirrorVertical,spec.geometry))
            }
            currentCoroutineContext().ensureActive()
            onProgress(50)
            if (!spec.colorAdjustments.isNeutral())
                replace(PhotoColorMath.apply(image(),spec.colorAdjustments))
            if (spec.localEffects.isNotEmpty())
                replace(PhotoLocalEffects.apply(image(),spec.localEffects))
            currentCoroutineContext().ensureActive()
            onProgress(75)
            val flatten = batch != null && format == BatchPhotoFormat.JPEG
            if (flatten || spec.graphicWatermark != null || spec.drawing.isNotEmpty() || spec.textLayers.isNotEmpty()) {
                val base = image()
                val composed = if (flatten) Bitmap.createBitmap(base.width,base.height,Bitmap.Config.ARGB_8888)
                    else base.copy(Bitmap.Config.ARGB_8888,true)
                try {
                    val canvas = Canvas(composed)
                    if (flatten) {
                        canvas.drawColor(android.graphics.Color.WHITE)
                        canvas.drawBitmap(base,0f,0f,null)
                    }
                    spec.graphicWatermark?.let {
                        PhotoGraphicWatermarkRenderer.draw(context,canvas,composed.width,composed.height,it)
                    }
                    if (spec.drawing.isNotEmpty()) PhotoDrawing.draw(canvas,composed.width,composed.height,spec.drawing)
                    if (spec.textLayers.isNotEmpty()) TextLayerRenderer.draw(context,canvas,
                        composed.width,composed.height,spec.textLayers,includeAll=true,fitInside=batch!=null)
                } catch (error: Throwable) { composed.recycle(); throw error }
                replace(composed)
            }
            currentCoroutineContext().ensureActive()
            val encoder = when (format) {
                BatchPhotoFormat.JPEG -> Bitmap.CompressFormat.JPEG
                BatchPhotoFormat.PNG -> Bitmap.CompressFormat.PNG
                BatchPhotoFormat.WEBP -> if (android.os.Build.VERSION.SDK_INT >= 30)
                    Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            }
            FileOutputStream(output).use {
                if (!image().compress(encoder,if(format==BatchPhotoFormat.PNG)100 else batch?.quality ?: 95,it))
                    throw IOException("Не удалось сохранить изображение.")
            }
            if (batch != null && format==BatchPhotoFormat.JPEG && !batch.removeExif && exif!=null)
                copyBatchExif(exif,output,batch.removeGps)
            currentCoroutineContext().ensureActive()
            if (output.length()==0L) throw IOException("Файл изображения пуст.")
            onProgress(100)
            PhotoExportResult(output,format.extension,format.mime)
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally { owned?.takeUnless { it.isRecycled }?.recycle() }
    }

    private fun copyBatchExif(source: android.media.ExifInterface, output: File, removeGps: Boolean) {
        val dest = android.media.ExifInterface(output.absolutePath)
        // Preserve the existing batch metadata contract: basic camera/date fields only.
        val tags = listOf("Make","Model","DateTime","DateTimeOriginal","ExposureTime","FNumber",
            "FocalLength","ISOSpeedRatings","WhiteBalance")
        tags.forEach { tag -> source.getAttribute(tag)?.let { dest.setAttribute(tag,it) } }
        if (!removeGps) listOf("GPSLatitude","GPSLatitudeRef","GPSLongitude","GPSLongitudeRef",
            "GPSAltitude","GPSAltitudeRef","GPSDateStamp","GPSTimeStamp").forEach { tag ->
            source.getAttribute(tag)?.let { dest.setAttribute(tag,it) }
        }
        dest.setAttribute(android.media.ExifInterface.TAG_ORIENTATION,"1")
        dest.saveAttributes()
    }
}

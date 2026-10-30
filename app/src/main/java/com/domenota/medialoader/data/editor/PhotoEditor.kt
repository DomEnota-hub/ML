package com.domenota.medialoader.data.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Matrix
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class PhotoEditSpec(
    val cropLeft: Float = 0f,
    val cropRight: Float = 0f,
    val cropTop: Float = 0f,
    val cropBottom: Float = 0f,
    val rotationDegrees: Int = 0,
    val mirrorHorizontal: Boolean = false,
    val mirrorVertical: Boolean = false,
    /** App-owned alpha PNG, never a third-party file path. */
    val inpaintOverlayPath: String? = null,
    val textLayers: List<TextLayer> = emptyList(),
    val colorAdjustments: PhotoColorAdjustments = PhotoColorAdjustments(),
) {
    init {
        require(textLayers.size <= 8)
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
    ): PhotoExportResult = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        onProgress(5)

        val mime = context.contentResolver.getType(source).orEmpty()
        val png = mime.equals("image/png", ignoreCase = true)
        val extension = if (png) "png" else "jpg"
        val outputMime = if (png) "image/png" else "image/jpeg"
        val output = File(context.cacheDir, "photo-edit-${UUID.randomUUID()}.$extension")

        var sourceBitmap: Bitmap? = null
        var cropped: Bitmap? = null
        var transformed: Bitmap? = null
        var withText: Bitmap? = null
        var withColor: Bitmap? = null
        try {
            sourceBitmap = context.contentResolver.openInputStream(source)?.use { input ->
                BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = spec.inpaintOverlayPath != null
                })
            } ?: throw IOException("Не удалось открыть изображение.")
            if (spec.inpaintOverlayPath != null) {
                val permittedDir = File(context.cacheDir, "inpainting-overlays").canonicalFile
                val overlayFile = File(spec.inpaintOverlayPath).canonicalFile
                if (overlayFile.parentFile != permittedDir || !overlayFile.isFile ||
                    !overlayFile.name.endsWith(".png")
                ) throw IOException("Не удалось прочитать рабочую маску редактора.")
                val overlay = BitmapFactory.decodeFile(overlayFile.absolutePath)
                    ?: throw IOException("Рабочая маска повреждена.")
                try {
                    val original = requireNotNull(sourceBitmap)
                    // Transparent mask areas leave original full-resolution pixels untouched.
                    Canvas(original).drawBitmap(overlay,
                        Rect(0, 0, overlay.width, overlay.height),
                        Rect(0, 0, original.width, original.height),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                } finally {
                    overlay.recycle()
                }
            }
            currentCoroutineContext().ensureActive()
            onProgress(25)

            val width = sourceBitmap.width
            val height = sourceBitmap.height
            val left = (width * spec.cropLeft).roundToInt().coerceIn(0, width - 1)
            val top = (height * spec.cropTop).roundToInt().coerceIn(0, height - 1)
            val right = (width * (1f - spec.cropRight)).roundToInt().coerceIn(left + 1, width)
            val bottom = (height * (1f - spec.cropBottom)).roundToInt().coerceIn(top + 1, height)

            cropped = if (left == 0 && top == 0 && right == width && bottom == height) {
                sourceBitmap
            } else {
                Bitmap.createBitmap(sourceBitmap, left, top, right - left, bottom - top)
            }
            if (cropped !== sourceBitmap) {
                sourceBitmap.recycle()
                sourceBitmap = null
            }

            currentCoroutineContext().ensureActive()
            onProgress(50)
            val rotation = ((spec.rotationDegrees % 360) + 360) % 360
            val needsTransform = rotation != 0 || spec.mirrorHorizontal || spec.mirrorVertical
            transformed = if (needsTransform) {
                val matrix = Matrix().apply {
                    if (spec.mirrorHorizontal || spec.mirrorVertical) {
                        postScale(
                            if (spec.mirrorHorizontal) -1f else 1f,
                            if (spec.mirrorVertical) -1f else 1f,
                        )
                    }
                    if (rotation != 0) postRotate(rotation.toFloat())
                }
                Bitmap.createBitmap(
                    requireNotNull(cropped),
                    0,
                    0,
                    requireNotNull(cropped).width,
                    requireNotNull(cropped).height,
                    matrix,
                    true,
                )
            } else {
                cropped
            }
            if (transformed !== cropped) {
                cropped?.recycle()
                cropped = null
            }

            currentCoroutineContext().ensureActive()
            onProgress(75)
            // Apply color to the photo before adding text; text colors remain untouched.
            withColor = if (spec.colorAdjustments.isNeutral()) null
                else PhotoColorMath.apply(requireNotNull(transformed), spec.colorAdjustments)
            if (spec.textLayers.isNotEmpty()) {
                val base = requireNotNull(withColor ?: transformed)
                withText = base.copy(Bitmap.Config.ARGB_8888, true)
                TextLayerRenderer.draw(context, Canvas(withText), withText.width, withText.height,
                    spec.textLayers, includeAll = true)
            }
            FileOutputStream(output).use { stream ->
                val ok = requireNotNull(withText ?: withColor ?: transformed).compress(
                    if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                    if (png) 100 else 95,
                    stream,
                )
                if (!ok) throw IOException("Не удалось сохранить изображение.")
            }
            if (!output.exists() || output.length() == 0L) throw IOException("Файл изображения пуст.")
            onProgress(100)
            PhotoExportResult(output, extension, outputMime)
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            withText?.recycle()
            withColor?.recycle()
            if (transformed != null && transformed !== cropped && transformed !== sourceBitmap) {
                transformed?.recycle()
            }
            if (cropped != null && cropped !== sourceBitmap) cropped?.recycle()
            sourceBitmap?.recycle()
        }
    }
}

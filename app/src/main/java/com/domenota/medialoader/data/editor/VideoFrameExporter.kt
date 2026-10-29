package com.domenota.medialoader.data.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
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

/** Extracts one source-resolution JPEG, applying the current video editor crop and rotation. */
internal class VideoFrameExporter(private val context: Context) {
    suspend fun export(source: Uri, positionMs: Long, spec: VideoEditSpec): File =
        withContext(Dispatchers.IO) {
            val output = File(context.cacheDir, "video-frame-${UUID.randomUUID()}.jpg")
            val retriever = MediaMetadataRetriever()
            var frame: Bitmap? = null
            var cropped: Bitmap? = null
            var rotated: Bitmap? = null
            var withText: Bitmap? = null
            try {
                retriever.setDataSource(context, source)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.coerceAtLeast(1L) ?: (positionMs + 1L)
                val safePosition = VideoFramePosition.clamp(positionMs, 0L, duration, duration)
                val timeUs = safePosition * 1_000L
                val exactFrame = try {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                } catch (_: RuntimeException) {
                    null
                }
                frame = exactFrame
                    ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: throw IOException("Не удалось получить кадр видео")
                currentCoroutineContext().ensureActive()

                val original = requireNotNull(frame)
                val left = (original.width * spec.cropLeft).roundToInt().coerceIn(0, original.width - 1)
                val top = (original.height * spec.cropTop).roundToInt().coerceIn(0, original.height - 1)
                val right = (original.width * (1f - spec.cropRight)).roundToInt()
                    .coerceIn(left + 1, original.width)
                val bottom = (original.height * (1f - spec.cropBottom)).roundToInt()
                    .coerceIn(top + 1, original.height)
                cropped = if (left == 0 && top == 0 && right == original.width && bottom == original.height)
                    original else Bitmap.createBitmap(original, left, top, right - left, bottom - top)

                val rotation = ((spec.rotationDegrees % 360) + 360) % 360
                rotated = if (rotation == 0) cropped else Bitmap.createBitmap(
                    requireNotNull(cropped), 0, 0, requireNotNull(cropped).width,
                    requireNotNull(cropped).height,
                    Matrix().apply { postRotate(rotation.toFloat()) }, true,
                )
                currentCoroutineContext().ensureActive()
                if (spec.textLayers.isNotEmpty()) {
                    withText = requireNotNull(rotated).copy(Bitmap.Config.ARGB_8888, true)
                    TextLayerRenderer.draw(context, android.graphics.Canvas(withText), withText.width,
                        withText.height, spec.textLayers, (safePosition - spec.startMs).coerceAtLeast(0L))
                }
                FileOutputStream(output).use { stream ->
                    if (!requireNotNull(withText ?: rotated).compress(Bitmap.CompressFormat.JPEG, 95, stream))
                        throw IOException("Не удалось сохранить кадр")
                }
                if (output.length() == 0L) throw IOException("Кадр видео пуст")
                output
            } catch (error: Throwable) {
                output.delete()
                throw error
            } finally {
                listOfNotNull(withText, rotated, cropped, frame).distinct().forEach { bitmap ->
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
                retriever.release()
            }
        }
}

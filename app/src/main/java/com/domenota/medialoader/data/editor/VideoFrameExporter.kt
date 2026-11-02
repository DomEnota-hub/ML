package com.domenota.medialoader.data.editor

import android.content.Context
import android.graphics.Bitmap
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

/** Render a bounded source segment through the SAME GPU pipeline as MP4, then save its frame.
 * Includes filters, FX, mirror, titles and graphics; never encodes the whole project for a JPEG.
 */
internal class VideoFrameExporter(private val context: Context) {
    suspend fun export(source: Uri, positionMs: Long, spec: VideoEditSpec): File = withContext(Dispatchers.IO) {
        val output = File(context.cacheDir, "video-frame-${UUID.randomUUID()}.jpg")
        var intermediate: File? = null
        var frame: Bitmap? = null
        try {
            val project = spec.project
            val location = project?.locateAt(positionMs.coerceIn(0, (project.durationMs - 1).coerceAtLeast(0)))
            val clip = location?.let { project.clips[it.clipIndex] }
            require(clip == null || clip.mediaKind == VideoClipMediaKind.VIDEO) {
                "Сохранение видеокадра доступно для видеоклипа"
            }
            val uri = clip?.sourceUri?.let(Uri::parse) ?: source
            val retriever = MediaMetadataRetriever()
            val duration = try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?.coerceAtLeast(1) ?: throw IOException("Неизвестна длительность видео")
            } finally { retriever.release() }
            val inMs = clip?.inMs ?: spec.startMs
            val outMs = (clip?.outMs ?: spec.endMs ?: duration).coerceAtMost(duration)
            val sourceMs = VideoFramePosition.clamp(location?.absoluteSourceMs ?: positionMs,
                inMs, outMs, duration)
            val overlays = if (project != null && location != null)
                VideoOverlayTimeline.forClip(project, location.clipIndex, spec.textLayers, spec.graphicLayers)
                else null
            val local = if (clip == null) spec else spec.copy(
                cropLeft = clip.crop.left, cropRight = clip.crop.right,
                cropTop = clip.crop.top, cropBottom = clip.crop.bottom,
                rotationDegrees = clip.rotationDegrees, mirrorHorizontal = clip.mirrorHorizontal,
                mirrorVertical = clip.mirrorVertical, color = clip.color, specialFx = clip.specialFx,
                textLayers = requireNotNull(overlays).texts, graphicLayers = overlays.graphics)
            // Include neighbouring source frames for VFR/end-of-file sampling, bounded to 300ms.
            val segmentStart = (sourceMs - 150L).coerceAtLeast(inMs)
            val segmentEnd = (sourceMs + 150L).coerceAtMost(outMs).coerceAtLeast(segmentStart + 1)
            val sampleSpec = local.copy(startMs = segmentStart, endMs = segmentEnd, project = null,
                reverse = false, removeAudio = true, sourceTimeOffsetMs = inMs,
                exportSettings = VideoExportSettings(quality = VideoExportQuality.HIGH))
            currentCoroutineContext().ensureActive()
            intermediate = withContext(Dispatchers.Main.immediate) { VideoEditor(context).export(uri, sampleSpec) }
            val rendered = MediaMetadataRetriever()
            frame = try {
                rendered.setDataSource(requireNotNull(intermediate).absolutePath)
                rendered.getFrameAtTime((sourceMs - segmentStart) * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST) ?: throw IOException("Не удалось получить кадр видео")
            } finally { rendered.release() }
            currentCoroutineContext().ensureActive()
            FileOutputStream(output).use { stream ->
                if (!requireNotNull(frame).compress(Bitmap.CompressFormat.JPEG, 95, stream))
                    throw IOException("Не удалось сохранить кадр")
            }
            if (output.length() == 0L) throw IOException("Кадр видео пуст")
            output
        } catch (error: Throwable) { output.delete(); throw error }
        finally { frame?.recycle(); intermediate?.delete() }
    }
}

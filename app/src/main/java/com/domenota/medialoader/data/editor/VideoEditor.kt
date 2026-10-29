package com.domenota.medialoader.data.editor

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.UUID
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class VideoEditSpec(
    val startMs: Long = 0L,
    val endMs: Long? = null,
    /** Fraction removed from each edge: 0f = keep edge, 0.25f = remove a quarter. */
    val cropLeft: Float = 0f,
    val cropRight: Float = 0f,
    val cropTop: Float = 0f,
    val cropBottom: Float = 0f,
    val rotationDegrees: Int = 0,
    val removeAudio: Boolean = false,
    val textLayers: List<TextLayer> = emptyList(),
) {
    init {
        require(textLayers.size <= 8)
        require(startMs >= 0)
        require(endMs == null || endMs > startMs)
        require(cropLeft in 0f..0.95f && cropRight in 0f..0.95f)
        require(cropTop in 0f..0.95f && cropBottom in 0f..0.95f)
        require(cropLeft + cropRight <= 0.95f)
        require(cropTop + cropBottom <= 0.95f)
        require(rotationDegrees % 90 == 0)
    }

    val hasCrop: Boolean
        get() = cropLeft > 0f || cropRight > 0f || cropTop > 0f || cropBottom > 0f
}

@OptIn(UnstableApi::class)
class VideoEditor(private val context: Context) {
    suspend fun export(
        source: Uri,
        spec: VideoEditSpec,
        onProgress: (Int) -> Unit = {},
    ): File {
        val mediaItem = clippedMediaItem(source, spec.startMs, spec.endMs)
        val edited = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(spec.removeAudio)
            .setEffects(Effects(emptyList(), previewEffects(spec)))
            .build()
        return runExport(edited, File(context.cacheDir, "video-edit-${UUID.randomUUID()}.mp4"),
            onProgress = onProgress)
    }

    internal fun previewEffects(spec: VideoEditSpec, timeOffsetMs: Long = 0L): List<Effect> {
        val videoEffects = mutableListOf<Effect>()
        if (spec.hasCrop) {
            videoEffects += Crop(
                -1f + 2f * spec.cropLeft,
                1f - 2f * spec.cropRight,
                -1f + 2f * spec.cropBottom,
                1f - 2f * spec.cropTop,
            )
        }
        val rotation = ((spec.rotationDegrees % 360) + 360) % 360
        if (rotation != 0) {
            videoEffects += ScaleAndRotateTransformation.Builder()
                // Android Canvas and the editor's RotateRight control use clockwise degrees.
                .setRotationDegrees(-rotation.toFloat())
                .build()
        }
        if (spec.textLayers.isNotEmpty()) {
            videoEffects += OverlayEffect(listOf(VideoTextOverlay(context, spec.textLayers, timeOffsetMs)))
        }
        return videoEffects
    }

    suspend fun extractAudio(
        source: Uri,
        startMs: Long = 0L,
        endMs: Long? = null,
        onProgress: (Int) -> Unit = {},
    ): File {
        val edited = EditedMediaItem.Builder(clippedMediaItem(source, startMs, endMs))
            .setRemoveVideo(true)
            .build()

        return runExport(
            edited = edited,
            output = File(context.cacheDir, "audio-extract-${UUID.randomUUID()}.m4a"),
            audioMimeType = MimeTypes.AUDIO_AAC,
            onProgress = onProgress,
        )
    }

    private fun clippedMediaItem(source: Uri, startMs: Long, endMs: Long?): MediaItem {
        require(startMs >= 0)
        require(endMs == null || endMs > startMs)
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs)
            .apply { endMs?.let(::setEndPositionMs) }
            .build()
        return MediaItem.Builder()
            .setUri(source)
            .setClippingConfiguration(clipping)
            .build()
    }

    private suspend fun runExport(
        edited: EditedMediaItem,
        output: File,
        audioMimeType: String? = null,
        onProgress: (Int) -> Unit,
    ): File = suspendCancellableCoroutine { continuation ->
        val mainHandler = Handler(Looper.getMainLooper())
        lateinit var transformer: Transformer

        val progressHolder = ProgressHolder()
        val progressPoll = object : Runnable {
            override fun run() {
                if (!continuation.isActive) return
                if (transformer.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(progressHolder.progress.coerceIn(0, 100))
                }
                mainHandler.postDelayed(this, 350L)
            }
        }

        val builder = Transformer.Builder(context)
        audioMimeType?.let(builder::setAudioMimeType)
        transformer = builder
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    mainHandler.removeCallbacks(progressPoll)
                    onProgress(100)
                    if (continuation.isActive) continuation.resume(output)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    mainHandler.removeCallbacks(progressPoll)
                    output.delete()
                    if (continuation.isActive) continuation.resumeWithException(exportException)
                }
            })
            .build()

        continuation.invokeOnCancellation {
            mainHandler.post {
                mainHandler.removeCallbacks(progressPoll)
                transformer.cancel()
                output.delete()
            }
        }

        runCatching {
            transformer.start(edited, output.absolutePath)
            mainHandler.post(progressPoll)
        }.onFailure { error ->
            mainHandler.removeCallbacks(progressPoll)
            output.delete()
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
}

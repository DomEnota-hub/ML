package com.domenota.medialoader.data.editor

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.C
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.UUID
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val mirrorHorizontal: Boolean = false,
    val mirrorVertical: Boolean = false,
    val color: VideoColorGrade = VideoColorGrade(),
    val removeAudio: Boolean = false,
    val reverse: Boolean = false,
    val textLayers: List<TextLayer> = emptyList(),
    val graphicLayers: List<VideoGraphicLayer> = emptyList(),
    val specialFx: VideoSpecialFxDraft = VideoSpecialFxDraft(),
    internal val transitionPhases: List<VideoTransitionPhase> = emptyList(),
    val project: VideoProjectDraft? = null,
    val exportSettings: VideoExportSettings = VideoExportSettings(),
) {
    init {
        require(textLayers.size <= 8)
        require(graphicLayers.size <= 12)
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
        val project = spec.project
        if (project != null) return exportProject(project, spec, onProgress)
        val mediaItem = clippedMediaItem(source, spec.startMs, spec.endMs)
        val sourceDimensions = withContext(Dispatchers.IO) {
            VideoExportSourceProbe.dimensions(context,
                VideoClipDraft("single", source.toString(),
                    (spec.endMs ?: (spec.startMs + 1L)).coerceAtLeast(spec.startMs + 1L)))
        }
        val size = spec.exportSettings.dimensions(sourceDimensions.first, sourceDimensions.second,
            1f - spec.cropLeft - spec.cropRight, 1f - spec.cropTop - spec.cropBottom,
            spec.rotationDegrees)
        val effects = previewEffects(spec) +
            Presentation.createForWidthAndHeight(
                size.width, size.height, Presentation.LAYOUT_SCALE_TO_FIT)
        val edited = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(spec.removeAudio)
            .setEffects(Effects(emptyList(), effects))
            .apply { spec.exportSettings.frameRate.maxFps?.let(::setFrameRate) }
            .build()
        val intermediate = runExport(edited = edited,
            output = File(context.cacheDir, "video-edit-${UUID.randomUUID()}.mp4"),
            exportSettings = spec.exportSettings, outputDimensions = size,
            onProgress = if (spec.reverse) ({ progress -> onProgress(progress * 45 / 100) }) else onProgress)
        if (!spec.reverse) return intermediate
        try {
            return VideoReverseEngine(context.cacheDir).reverse(intermediate, onProgress)
        } finally {
            intermediate.delete()
        }
    }

    /**
     * Media3 1.9 Composition concatenates edited items into a real playable
     * file, instead of merging thumbnail previews or exporting only clip #1.
     */
    private suspend fun exportProject(
        project: VideoProjectDraft,
        spec: VideoEditSpec,
        onProgress: (Int) -> Unit,
    ): File {
        val first = project.clips.first()
        val dimensions = withContext(Dispatchers.IO) {
            VideoExportSourceProbe.dimensions(context, first)
        }
        val canvas = spec.exportSettings.dimensions(
            dimensions.first, dimensions.second,
            first.crop.keptWidth, first.crop.keptHeight, first.rotationDegrees)
        val composition = compositionForProject(project, spec, canvas)
        val destination = File(context.cacheDir, "video-join-${UUID.randomUUID()}.mp4")
        val withOverlay = project.visibleAudioTracks().isNotEmpty()
        val firstPassMax = when {
            spec.reverse && withOverlay -> 40
            spec.reverse -> 45
            withOverlay -> 75
            else -> 100
        }
        var working = runExport(composition = composition, output = destination,
            exportSettings = spec.exportSettings, outputDimensions = canvas,
            onProgress = { p -> onProgress(p * firstPassMax / 100) })
        try {
            if (withOverlay) {
                val overlayMax = if (spec.reverse) 60 else 100
                val mixed = VideoAudioMixEngine(context).mix(working, project,
                    includeOriginalAudio = !spec.removeAudio) { p ->
                    onProgress(firstPassMax + p * (overlayMax - firstPassMax) / 100)
                }
                if (mixed != working) {
                    working.delete()
                    working = mixed
                }
            }
            if (!spec.reverse) return working
            val reverseStartsAt = if (withOverlay) 60 else 45
            return VideoReverseEngine(context.cacheDir).reverse(working) { p ->
                onProgress(reverseStartsAt +
                    (p - 45).coerceIn(0, 55) * (100 - reverseStartsAt) / 55)
            }
        } catch (error: Throwable) {
            working.delete()
            throw error
        } finally {
            if (spec.reverse) working.delete()
        }
    }

    internal fun compositionForProject(
        project: VideoProjectDraft,
        spec: VideoEditSpec,
        outputDimensions: VideoExportDimensions? = null,
    ): Composition {
        val clips = project.clips
        val edited = clips.mapIndexed { index, clip ->
            val localOverlays = VideoOverlayTimeline.forClip(
                project, index, spec.textLayers, spec.graphicLayers)
            val localSpec = spec.copy(
                textLayers = localOverlays.texts,
                graphicLayers = localOverlays.graphics,
                project = null,
                startMs = clip.inMs, endMs = clip.outMs,
                cropLeft = clip.crop.left, cropRight = clip.crop.right,
                cropTop = clip.crop.top, cropBottom = clip.crop.bottom,
                rotationDegrees = clip.rotationDegrees,
                mirrorHorizontal = clip.mirrorHorizontal,
                mirrorVertical = clip.mirrorVertical,
                color = clip.color,
                specialFx = clip.specialFx,
                transitionPhases = VideoTransitionTiming.forClip(project, index))
            val mediaItem = if (clip.mediaKind == VideoClipMediaKind.IMAGE) {
                MediaItem.Builder().setUri(Uri.parse(clip.sourceUri))
                    .setImageDurationMs(clip.durationMs).build()
            } else clippedMediaItem(Uri.parse(clip.sourceUri), clip.inMs, clip.outMs)
            val effects = previewEffects(localSpec, timeOffsetMs = clip.inMs)
            val unifiedEffects = if (outputDimensions == null) effects
                else effects + Presentation.createForWidthAndHeight(
                    outputDimensions.width, outputDimensions.height,
                    Presentation.LAYOUT_SCALE_TO_FIT)
            EditedMediaItem.Builder(mediaItem)
                .setRemoveAudio(spec.removeAudio)
                .setEffects(Effects(emptyList(), unifiedEffects))
                .apply {
                    if (clip.mediaKind == VideoClipMediaKind.IMAGE) {
                        setFrameRate(spec.exportSettings.frameRate.maxFps ?: 30)
                    } else {
                        spec.exportSettings.frameRate.maxFps?.let(::setFrameRate)
                    }
                    if (clip.speed != 1f) {
                        setSpeed(object : SpeedProvider {
                            override fun getSpeed(timeUs: Long): Float = clip.speed
                            override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
                        })
                    }
                }
                .build()
        }
        val sequence = EditedMediaItemSequence.withAudioAndVideoFrom(edited)
        val composition = Composition.Builder(sequence).build()
        return composition
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
        if (rotation != 0 || spec.mirrorHorizontal || spec.mirrorVertical) {
            videoEffects += ScaleAndRotateTransformation.Builder()
                // Android Canvas and the editor's RotateRight control use clockwise degrees.
                .setRotationDegrees(-rotation.toFloat())
                .setScale(if (spec.mirrorHorizontal) -1f else 1f,
                    if (spec.mirrorVertical) -1f else 1f)
                .build()
        }
        videoEffects += colorEffects(spec.color)
        val timedOverlays = mutableListOf<androidx.media3.effect.TextureOverlay>()
        if (spec.specialFx.enabled) timedOverlays += VideoSpecialFxOverlay(
            spec.specialFx, timeOffsetMs)
        if (spec.textLayers.isNotEmpty() || spec.graphicLayers.isNotEmpty()) {
            timedOverlays += VideoTextOverlay(context,
                spec.textLayers, timeOffsetMs, spec.graphicLayers)
        }
        // Apply the final transition over titles too: cut frames fade together.
        spec.transitionPhases.forEach { phase ->
            timedOverlays += VideoTransitionOverlay(phase, timeOffsetMs)
        }
        if (timedOverlays.isNotEmpty()) videoEffects += OverlayEffect(timedOverlays)
        return videoEffects
    }

    /** A shared Media3 effect pipeline is used for player preview and export. */
    internal fun colorEffects(color: VideoColorGrade): List<Effect> {
        if (color.isNeutral) return emptyList()
        val effects = mutableListOf<Effect>()
        val photoPreset = color.photoEffect
        if (photoPreset != null && photoPreset != PhotoEffect.NONE && color.intensity > 0f) {
            effects += VideoPhotoPresetEffect(photoPreset, color.intensity)
        }
        // Apply manual video controls independently; do not approximate or double-apply
        // the selected photo preset through the legacy video RGB/HSL adapter.
        val adjusted = if (photoPreset != null)
            color.copy(photoEffect = PhotoEffect.NONE).resolved() else color.resolved()
        if (adjusted.brightness != 0f) effects += Brightness(adjusted.brightness)
        if (adjusted.contrast != 0f) effects += Contrast(adjusted.contrast)
        if (adjusted.red != 1f || adjusted.green != 1f || adjusted.blue != 1f) {
            effects += RgbAdjustment.Builder()
                .setRedScale(adjusted.red)
                .setGreenScale(adjusted.green)
                .setBlueScale(adjusted.blue)
                .build()
        }
        if (adjusted.hue != 0f || adjusted.saturation != 0f ||
            adjusted.lightness != 0f) {
            effects += HslAdjustment.Builder()
                .adjustHue(adjusted.hue)
                .adjustSaturation(adjusted.saturation)
                .adjustLightness(adjusted.lightness)
                .build()
        }
        return effects
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
        edited: EditedMediaItem? = null,
        composition: Composition? = null,
        output: File,
        audioMimeType: String? = null,
        exportSettings: VideoExportSettings? = null,
        outputDimensions: VideoExportDimensions? = null,
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
        if (exportSettings != null && outputDimensions != null) {
            val bitrate = exportSettings.targetBitrate(outputDimensions,
                exportSettings.frameRate.maxFps ?: 30)
            builder.setVideoMimeType(MimeTypes.VIDEO_H264)
            builder.setEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .setEnableFallback(true)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                    .build())
        }
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
            if (composition != null) transformer.start(composition, output.absolutePath)
            else transformer.start(requireNotNull(edited), output.absolutePath)
            mainHandler.post(progressPoll)
        }.onFailure { error ->
            mainHandler.removeCallbacks(progressPoll)
            output.delete()
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
}

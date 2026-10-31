package com.domenota.medialoader.data.editor

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Verify the actual MP4, not a Transformer's reported progress. Encoder fallback,
 * mixed media and FFmpeg reverse can change actual output characteristics.
 * A playable nonempty video track is mandatory; normal encoder fallbacks are warnings.
 */
internal data class VideoExportInspection(
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val bitrate: Int?,
    val frameRate: Float?,
    val warnings: List<String>,
) {
    val summary: String get() = buildString {
        append("${width}×${height}")
        frameRate?.let { append(" · ${"%.1f".format(java.util.Locale.US, it)} FPS") }
        bitrate?.takeIf { it > 0 }?.let {
            append(" · ${"%.1f".format(java.util.Locale.US, it / 1_000_000f)} Мбит/с")
        }
        if (warnings.isNotEmpty()) append(" · параметры уточнены по файлу")
    }
}

/** Pure checks also run in Robolectric without a hardware video encoder. */
internal object VideoExportInspectionPolicy {
    fun evaluate(
        width: Int, height: Int, durationMs: Long, hasVideo: Boolean,
        bitrate: Int?, frameRate: Float?,
        expectedDurationMs: Long?, expectedSize: VideoExportDimensions?,
        expectedFrameRate: Int?,
    ): VideoExportInspection {
        require(hasVideo && width > 0 && height > 0 && durationMs > 0) {
            "Экспорт не создал пригодную видеодорожку"
        }
        val warnings = mutableListOf<String>()
        if (expectedDurationMs != null && expectedDurationMs > 0L) {
            val tolerance = max(1_500L, expectedDurationMs / 10L)
            if (abs(durationMs - expectedDurationMs) > tolerance)
                warnings += "Длительность видео отличается от проекта"
        }
        if (expectedSize != null) {
            val actualRatio = width.toDouble() / height
            val expectedRatio = expectedSize.width.toDouble() / expectedSize.height
            if (abs(actualRatio - expectedRatio) > .025)
                warnings += "Соотношение сторон отличается от выбранного"
            if (width != expectedSize.width || height != expectedSize.height)
                warnings += "Кодек изменил выходное разрешение"
        }
        if (expectedFrameRate != null && frameRate != null &&
            frameRate > expectedFrameRate + 1f)
            warnings += "FPS превышает запрошенный предел"
        return VideoExportInspection(width, height, durationMs,
            bitrate?.takeIf { it > 0 }, frameRate?.takeIf { it.isFinite() && it > 0f },
            warnings.distinct())
    }
}

internal class VideoExportFileInspector(private val context: Context) {
    fun inspect(file: File, spec: VideoEditSpec, expectedSize: VideoExportDimensions? = null)
        : VideoExportInspection {
        require(file.isFile && file.length() > 4096L) {
            "Экспорт не создал непустой видеофайл"
        }
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(file.absolutePath)
            fun metadata(key: Int) = reader.extractMetadata(key)
            val rotation = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val rawW = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val rawH = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rotated = rotation % 180 != 0
            val width = if (rotated) rawH else rawW
            val height = if (rotated) rawW else rawH
            val projectDuration = spec.project?.durationMs ?:
                spec.endMs?.let { it - spec.startMs }
            val actual = VideoExportInspectionPolicy.evaluate(
                width = width, height = height,
                durationMs = metadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
                hasVideo = metadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                    ?.equals("yes", ignoreCase = true) == true,
                bitrate = metadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull(),
                frameRate = metadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull(),
                expectedDurationMs = projectDuration,
                expectedSize = expectedSize,
                expectedFrameRate = spec.exportSettings.frameRate.maxFps)
            return actual
        } finally { reader.release() }
    }
}

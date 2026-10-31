package com.domenota.medialoader.data.editor

import kotlin.math.roundToInt

enum class VideoExportResolution(val label: String, val shortSidePx: Int?) {
    ORIGINAL("По исходнику", null),
    SD_480("480p", 480),
    HD_720("720p", 720),
    FULL_HD_1080("1080p", 1080),
    ULTRA_HD_2160("2160p", 2160),
}

enum class VideoExportFrameRate(val label: String, val maxFps: Int?) {
    SOURCE("Как в исходнике", null),
    FPS_24("24 FPS", 24),
    FPS_30("30 FPS", 30),
    FPS_60("60 FPS", 60),
}

enum class VideoExportQuality(val label: String, val rateFactor: Double) {
    COMPACT("Экономия места", .065),
    BALANCED("Сбалансированное", .105),
    HIGH("Высокое", .18),
}

/**
 * Requested encoding settings; actual media encoder may fallback depending
 * on GPU/codec capabilities. This never claims to increase intrinsic FPS.
 */
data class VideoExportSettings(
    val resolution: VideoExportResolution = VideoExportResolution.ORIGINAL,
    val frameRate: VideoExportFrameRate = VideoExportFrameRate.SOURCE,
    val quality: VideoExportQuality = VideoExportQuality.BALANCED,
) {
    fun dimensions(firstWidth: Int, firstHeight: Int,
        keptWidth: Float = 1f, keptHeight: Float = 1f,
        rotationDegrees: Int = 0): VideoExportDimensions {
        require(firstWidth > 0 && firstHeight > 0)
        require(keptWidth.isFinite() && keptWidth in .05f..1f)
        require(keptHeight.isFinite() && keptHeight in .05f..1f)
        require(rotationDegrees % 90 == 0)
        val croppedW = (firstWidth * keptWidth).roundToInt().coerceAtLeast(2)
        val croppedH = (firstHeight * keptHeight).roundToInt().coerceAtLeast(2)
        val rotated = rotationDegrees % 180 != 0
        val sourceW = if (rotated) croppedH else croppedW
        val sourceH = if (rotated) croppedW else croppedH
        val shortSide = resolution.shortSidePx
        val scale = if (shortSide == null) 1.0
            else shortSide.toDouble() / minOf(sourceW, sourceH)
        fun even(value: Double): Int =
            (value.roundToInt().coerceAtLeast(2) + 1) / 2 * 2
        return VideoExportDimensions(even(sourceW * scale), even(sourceH * scale))
    }

    /** Target VBR bitrate, not an assertion of the actual encoder output bitrate. */
    fun targetBitrate(size: VideoExportDimensions, estimatedFps: Int = 30): Int {
        val fps = frameRate.maxFps ?: estimatedFps.coerceIn(12, 120)
        return (size.width.toDouble() * size.height * fps * quality.rateFactor)
            .roundToInt().coerceIn(600_000, 65_000_000)
    }
}

data class VideoExportDimensions(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) }
    val aspect: Float get() = width.toFloat() / height
}

/** Source file kinds are explicit; no heuristic from file name or URI path. */
enum class VideoClipMediaKind { VIDEO, IMAGE }

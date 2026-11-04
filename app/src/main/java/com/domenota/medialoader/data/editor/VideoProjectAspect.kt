package com.domenota.medialoader.data.editor

import kotlin.math.max
import kotlin.math.roundToInt

/** The eight project canvas ratios in approved HTML v8, independent of clip crop. */
enum class VideoProjectAspect(val label: String, val ratio: Float?) {
    ORIGINAL("Исходное", null), SQUARE("1:1", 1f), PORTRAIT_3_4("3:4", .75f),
    LANDSCAPE_4_3("4:3", 4f / 3f), LANDSCAPE_16_9("16:9", 16f / 9f),
    PORTRAIT_9_16("9:16", 9f / 16f), CINEMA_239("2.39:1", 2.39f), CINEMA_255("2.55:1", 2.55f);

    fun dimensions(source: VideoExportDimensions, requestedShortSide: Int? = null): VideoExportDimensions {
        val r = ratio ?: return source
        val longSide = requestedShortSide?.let { if (r >= 1) it * r else it / r }
            ?: max(source.width, source.height).toFloat()
        fun even(value: Float) = (value.roundToInt().coerceAtLeast(2) + 1) / 2 * 2
        return if (r >= 1) VideoExportDimensions(even(longSide), even(longSide / r))
            else VideoExportDimensions(even(longSide * r), even(longSide))
    }
}

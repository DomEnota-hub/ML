package com.domenota.medialoader.ui.screens

import kotlin.math.min

/** Exact RESIZE_MODE_FIT rectangle in the viewport, no invented aspect-ratio clamping. */
internal data class VideoPreviewFit(val width: Float, val height: Float)

internal fun fitVideoPreview(
    maxWidth: Float, maxHeight: Float, sourceWidth: Int, sourceHeight: Int,
): VideoPreviewFit {
    if (maxWidth <= 0f || maxHeight <= 0f || sourceWidth <= 0 || sourceHeight <= 0) {
        return VideoPreviewFit(0f, 0f)
    }
    val scale = min(maxWidth / sourceWidth, maxHeight / sourceHeight)
    return VideoPreviewFit(sourceWidth * scale, sourceHeight * scale)
}

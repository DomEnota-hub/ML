package com.domenota.medialoader.data.editor

import java.util.Locale

internal data class ImagePixel(val x: Int, val y: Int)

/** Maps an aspect-fit preview back to its decoded bitmap; letterboxes have no source pixel. */
internal object ImageColorSample {
    fun pixel(width: Int, height: Int, viewWidth: Float, viewHeight: Float, x: Float, y: Float): ImagePixel? {
        if (width <= 0 || height <= 0 || !viewWidth.isFinite() || !viewHeight.isFinite() ||
            viewWidth <= 0f || viewHeight <= 0f || !x.isFinite() || !y.isFinite()) return null
        val scale = minOf(viewWidth / width, viewHeight / height)
        val px = (x - (viewWidth - width * scale) / 2f) / scale
        val py = (y - (viewHeight - height * scale) / 2f) / scale
        if (px < 0f || py < 0f || px >= width || py >= height) return null
        return ImagePixel(px.toInt(), py.toInt())
    }
    fun hex(color: Int): String = if (color ushr 24 == 255)
        String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)
    else String.format(Locale.ROOT, "#%08X", color)
}

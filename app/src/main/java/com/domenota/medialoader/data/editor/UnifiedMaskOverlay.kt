package com.domenota.medialoader.data.editor

import android.graphics.Bitmap

/**
 * A single union of all brush strokes. Each covered pixel receives ONE
 * translucent yellow color, regardless of how often the user crosses it.
 *
 * Drawing outlined circles around every input sample is incorrect: the outlines
 * remain visible inside overlaps, and repeated alpha blends make the mask opaque.
 */
object UnifiedMaskOverlay {
    // ~34% yellow in the interior, strong narrow golden edge.
    private const val FILL = 0x57FFD500
    private val EDGE = 0xD9FFCE00.toInt()

    /** Pure pixel compositor, also exercised by ordinary JVM unit tests. */
    fun colorize(coverage: IntArray, width: Int, height: Int): IntArray {
        require(width > 0 && height > 0 && coverage.size == width * height)
        val selected = BooleanArray(coverage.size) { index ->
            (coverage[index] ushr 24) > 10
        }
        val output = IntArray(coverage.size)
        fun selectedAt(x: Int, y: Int): Boolean =
            x >= 0 && x < width && y >= 0 && y < height && selected[y * width + x]

        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val inside = selected[i]
                // Edge only at the perimeter of the WHOLE merged selection.
                // Interior overlaps do not create outlines or accumulate opacity.
                val bordersOther = if (inside) {
                    !selectedAt(x - 2, y) || !selectedAt(x + 2, y) ||
                        !selectedAt(x, y - 2) || !selectedAt(x, y + 2)
                } else {
                    selectedAt(x - 1, y) || selectedAt(x + 1, y) ||
                        selectedAt(x, y - 1) || selectedAt(x, y + 1)
                }
                output[i] = when {
                    bordersOther -> EDGE
                    inside -> FILL
                    else -> 0
                }
            }
        }
        return output
    }

    /**
     * Rasterize at PREVIEW resolution to avoid blocking touch events with full
     * original-resolution bitmap allocations. Actual inpainting masks remain
     * created separately at the image's working resolution.
     */
    fun render(strokes: List<MaskStroke>, imageWidth: Int, imageHeight: Int): Bitmap? {
        if (strokes.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return null
        val maxSide = maxOf(imageWidth, imageHeight)
        val scale = minOf(1f, 640f / maxSide)
        val width = (imageWidth * scale).toInt().coerceAtLeast(1)
        val height = (imageHeight * scale).toInt().coerceAtLeast(1)
        val mask = LaMaInpaintingEngine.rasterizeMask(strokes, width, height)
        return try {
            val pixels = IntArray(width * height)
            mask.getPixels(pixels, 0, width, 0, 0, width, height)
            if (pixels.none { (it ushr 24) > 10 }) return null
            Bitmap.createBitmap(colorize(pixels, width, height), width, height,
                Bitmap.Config.ARGB_8888)
        } finally {
            mask.recycle()
        }
    }
}

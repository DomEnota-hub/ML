package com.domenota.medialoader.data.editor

import android.graphics.Bitmap

/**
 * Real MobileSAM silhouette edge, not the detector's rough bounding rectangle.
 * An unselected person stays outlined so they can be chosen without painting
 * a misleading yellow rectangle over the background.
 */
internal object PersonContourOverlay {
    private val YELLOW = 0xFFFFC21C.toInt()
    private val WHITE = 0xF8FFFFFF.toInt()
    private val UNCERTAIN = 0xFF919191.toInt()
    // Match the pixels that LaMa will actually remove, not just the perimeter.
    private val SELECTED_FILL = 0x58FFC21C.toInt()

    /** No Android graphics dependency in this function: JVM-testable pixels. */
    fun contour(coverage: IntArray, width: Int, height: Int, argb: Int): IntArray {
        require(width > 0 && height > 0 && coverage.size == width * height)
        val mask = BooleanArray(coverage.size) { (coverage[it] ushr 24) > 10 }
        val out = IntArray(coverage.size)
        fun filled(x: Int, y: Int) =
            x in 0 until width && y in 0 until height && mask[y * width + x]
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            if (!mask[i]) continue
            if (!filled(x - 1, y) || !filled(x + 1, y) ||
                !filled(x, y - 1) || !filled(x, y + 1)) {
                out[i] = argb
            }
        }
        return out
    }

    /**
     * A selected person is previewed with a translucent interior in exactly
     * the rasterized mask, while unselected/uncertain people remain outlines.
     * This pure pixel pass is covered by JVM tests and never expands the mask.
     */
    fun paintCoverage(
        coverage: IntArray, width: Int, height: Int,
        selected: Boolean, edgeArgb: Int,
    ): IntArray {
        val edges = contour(coverage, width, height, edgeArgb)
        if (!selected) return edges
        for (i in edges.indices) {
            if (edges[i] == 0 && (coverage[i] ushr 24) > 10) {
                edges[i] = SELECTED_FILL
            }
        }
        return edges
    }

    fun render(
        people: List<PersonInstance>, selected: Set<Int>,
        ambiguous: Set<Int>, originalWidth: Int, originalHeight: Int,
    ): Bitmap? {
        if (people.isEmpty() || originalWidth <= 0 || originalHeight <= 0) return null
        val scale = minOf(1f, 512f / maxOf(originalWidth, originalHeight))
        val width = (originalWidth * scale).toInt().coerceAtLeast(1)
        val height = (originalHeight * scale).toInt().coerceAtLeast(1)
        val result = IntArray(width * height)
        // One bitmap at a time; no masks survive outside the preview pass.
        for (person in people) {
            val mask = LaMaInpaintingEngine.rasterizeMask(person.strokes, width, height)
            try {
                val coverage = IntArray(result.size)
                mask.getPixels(coverage, 0, width, 0, 0, width, height)
                val color = when {
                    person.detection.id in ambiguous -> UNCERTAIN
                    person.detection.id in selected -> YELLOW
                    else -> WHITE
                }
                val pixels = paintCoverage(coverage, width, height,
                    person.detection.id in selected && person.detection.id !in ambiguous,
                    color)
                for (i in pixels.indices) if (pixels[i] != 0) result[i] = pixels[i]
            } finally {
                mask.recycle()
            }
        }
        return Bitmap.createBitmap(result, width, height, Bitmap.Config.ARGB_8888)
    }
}

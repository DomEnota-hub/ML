package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Conservative source-pixel mask for OCR symbol candidates.
 *
 * Rectangles are search limits, NEVER masks. This only makes a mask when a
 * locally uniform background can be distinguished from visible ink. Text on
 * a textured/ambiguous background is deliberately left for manual correction
 * or a verified segmentation model; it must not erase scenery by guessing.
 */
internal object GlyphContentMask {
    private const val MAX_SYMBOLS = 160
    private const val MAX_PIXELS_PER_SYMBOL = 600_000
    private const val MAX_STROKES = 8_000

    fun fromBitmap(source: Bitmap, glyphs: Collection<DetectedGlyph>): List<MaskStroke> {
        if (glyphs.isEmpty() || glyphs.size > MAX_SYMBOLS ||
            source.width < 2 || source.height < 2) return emptyList()
        val result = ArrayList<MaskStroke>()
        for (glyph in glyphs) {
            val segment = extractSymbol(source, glyph) ?: return emptyList()
            if (segment.isEmpty() || result.size + segment.size > MAX_STROKES) {
                // No partial removal of a selected phrase.
                return emptyList()
            }
            result.addAll(segment)
        }
        return result
    }

    private fun extractSymbol(source: Bitmap, glyph: DetectedGlyph): List<MaskStroke>? {
        val width = source.width
        val height = source.height
        val left = (glyph.left * width).toInt().coerceIn(0, width - 1)
        val top = (glyph.top * height).toInt().coerceIn(0, height - 1)
        val right = (glyph.right * width).toInt().coerceIn(left + 1, width)
        val bottom = (glyph.bottom * height).toInt().coerceIn(top + 1, height)
        val short = min(width, height).toFloat()
        val pad = (min(right - left, bottom - top) * .18f).toInt().coerceIn(2, 16)
        val x0 = max(0, left - pad)
        val y0 = max(0, top - pad)
        val x1 = min(width, right + pad)
        val y1 = min(height, bottom + pad)
        val regionWidth = x1 - x0
        val regionHeight = y1 - y0
        if (regionWidth < 5 || regionHeight < 5 ||
            regionWidth.toLong() * regionHeight > MAX_PIXELS_PER_SYMBOL) return null

        val pixels = IntArray(regionWidth * regionHeight)
        source.getPixels(pixels, 0, regionWidth, x0, y0, regionWidth, regionHeight)
        val boundary = ArrayList<Int>()
        val xStep = max(1, regionWidth / 12)
        val yStep = max(1, regionHeight / 12)
        for (x in 0 until regionWidth step xStep) {
            boundary.add(pixels[x])
            boundary.add(pixels[(regionHeight - 1) * regionWidth + x])
        }
        for (y in 0 until regionHeight step yStep) {
            boundary.add(pixels[y * regionWidth])
            boundary.add(pixels[y * regionWidth + regionWidth - 1])
        }
        val background = medianColor(boundary)
        // The four-sided background sample is not trustworthy if it contains
        // texture, multiple foreground objects or adjoining glyphs.
        val backgroundSpread = boundary.sumOf { distance(it, background) } / boundary.size
        if (backgroundSpread > 35) return null

        val foreground = BooleanArray(pixels.size)
        var ink = 0
        for (index in pixels.indices) {
            val color = pixels[index]
            val visible = ((color ushr 24) and 255) >= 200 &&
                distance(color, background) >= 43
            foreground[index] = visible
            if (visible) ink++
        }
        // Blank or largely selected rectangles are unsafe. Require that the
        // visible ink has a plausible occupancy within a character region.
        if (ink < 3 || ink * 100L < pixels.size.toLong() ||
            ink * 100L > pixels.size.toLong() * 62) return null

        val strokes = ArrayList<MaskStroke>()
        val diameter = (2f / short).coerceIn(.00001f, .5f)
        for (y in 0 until regionHeight) {
            var x = 0
            while (x < regionWidth) {
                if (!foreground[y * regionWidth + x]) {
                    x++
                    continue
                }
                val start = x
                while (x < regionWidth && foreground[y * regionWidth + x]) x++
                val end = x - 1
                val yy = (y0 + y + .5f) / height
                val first = MaskPoint((x0 + start + .5f) / width, yy)
                val last = MaskPoint((x0 + end + .5f) / width, yy)
                strokes.add(MaskStroke(if (start == end) listOf(first) else listOf(first, last),
                    diameter))
                if (strokes.size > MAX_STROKES) return null
            }
        }
        return strokes
    }

    private fun medianColor(colors: List<Int>): Int {
        fun channel(shift: Int): Int =
            colors.map { (it ushr shift) and 255 }.sorted()[colors.size / 2]
        return (255 shl 24) or (channel(16) shl 16) or
            (channel(8) shl 8) or channel(0)
    }

    private fun distance(a: Int, b: Int): Int =
        (abs(((a ushr 16) and 255) - ((b ushr 16) and 255)) +
            abs(((a ushr 8) and 255) - ((b ushr 8) and 255)) +
            abs((a and 255) - (b and 255))) / 3
}

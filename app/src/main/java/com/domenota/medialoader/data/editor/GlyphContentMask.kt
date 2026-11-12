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
        // A tight symbol perimeter can cross its neighbour. Only use a line
        // background inferred from the entire outer ring of a credible line.
        val backgrounds = mutableMapOf<Int, Background>()
        for (line in OcrLineCandidatePolicy.credibleLines(glyphs.toList())) {
            if (line.count { it.confidence >= 75f && it.text.any(Char::isLetterOrDigit) } < 4) continue
            val bg = lineBackground(source, line) ?: continue
            line.forEach { backgrounds[it.id] = bg }
        }
        for (glyph in glyphs) {
            val segment = extractSymbol(source, glyph) ?: backgrounds[glyph.id]?.let {
                extractSymbol(source, glyph, it)
            } ?: return emptyList()
            if (segment.isEmpty() || result.size + segment.size > MAX_STROKES) {
                // No partial removal of a selected phrase.
                return emptyList()
            }
            result.addAll(segment)
        }
        return result
    }

    private data class Background(val color: Int, val q75: Int)

    private fun verifiedBackground(boundary: List<Int>): Background? {
        if (boundary.size < 12) return null
        val color = medianColor(boundary)
        val deviations = boundary.map { distance(it, color) }.sorted()
        if (deviations[deviations.size / 2] > 18 ||
            deviations.count { it <= 30 } * 100L < deviations.size * 72L) return null
        return Background(color, deviations[(deviations.size - 1) * 3 / 4])
    }

    private fun lineBackground(source: Bitmap, line: List<DetectedGlyph>): Background? {
        val width = source.width; val height = source.height
        val l = (line.minOf { it.left } * width).toInt()
        val r = kotlin.math.ceil(line.maxOf { it.right } * width).toInt()
        val t = (line.minOf { it.top } * height).toInt()
        val b = kotlin.math.ceil(line.maxOf { it.bottom } * height).toInt()
        val pad = ((b - t) * .30f).toInt().coerceIn(3, 24)
        val x0 = (l - pad).coerceAtLeast(0); val x1 = (r + pad).coerceAtMost(width)
        val y0 = (t - pad).coerceAtLeast(0); val y1 = (b + pad).coerceAtMost(height)
        if (x1 - x0 < 5 || y1 - y0 < 5 || (x1 - x0).toLong() * (y1 - y0) > MAX_PIXELS_PER_SYMBOL) return null
        // Full perimeter, plus the genuine exterior band, not a decimated grid.
        val pixels = IntArray((x1 - x0) * (y1 - y0))
        source.getPixels(pixels, 0, x1 - x0, x0, y0, x1 - x0, y1 - y0)
        val samples = ArrayList<Int>()
        for (y in y0 until y1) for (x in x0 until x1) {
            if (x < l || x >= r || y < t || y >= b) samples.add(pixels[(y-y0)*(x1-x0)+x-x0])
        }
        return verifiedBackground(samples)?.takeIf { it.q75 <= 18 }
    }

    private fun extractSymbol(source: Bitmap, glyph: DetectedGlyph, lineBackground: Background? = null): List<MaskStroke>? {
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
        // Sample the entire perimeter, not a regularly decimated grid.
        // Even strides can sample only one colour of a periodic texture
        // (e.g. a checkerboard), making a noisy background appear uniform.
        val boundary = ArrayList<Int>(2 * (regionWidth + regionHeight) - 4)
        for (x in 0 until regionWidth) {
            boundary.add(pixels[x])
            boundary.add(pixels[(regionHeight - 1) * regionWidth + x])
        }
        for (y in 1 until regionHeight - 1) {
            boundary.add(pixels[y * regionWidth])
            boundary.add(pixels[y * regionWidth + regionWidth - 1])
        }
        val estimate = lineBackground ?: verifiedBackground(boundary) ?: return null
        val background = estimate.color
        val q75 = estimate.q75
        if (lineBackground != null) {
            // The alternative is permitted only where the character itself
            // still contains real background evidence. Never fill a box.
            if (glyph.confidence < 75f || pixels.count { distance(it, background) <= 18 } * 100L < pixels.size * 20L) return null
        }

        // A thin grey outline can be much less than 43 RGB levels from
        // the background. Lower the threshold only for a coherent OCR
        // candidate with locally consistent background, never globally.
        val threshold = if (glyph.confidence >= 65f && q75 <= 18)
            (q75 * 2 + 6).coerceIn(14, 43) else 43
        val foreground = BooleanArray(pixels.size)
        val margin = min(2, pad)
        for (index in pixels.indices) {
            val x = x0 + index % regionWidth
            val y = y0 + index / regionWidth
            val color = pixels[index]
            foreground[index] = x in (left - margin) until (right + margin) &&
                y in (top - margin) until (bottom + margin) &&
                ((color ushr 24) and 255) >= 200 && distance(color, background) >= threshold
        }
        // Remove isolated single-pixel noise without filling glyph holes or
        // discarding real punctuation/diacritics with neighbouring pixels.
        val coherent = foreground.copyOf()
        for (index in foreground.indices) if (foreground[index]) {
            val x = index % regionWidth; val y = index / regionWidth
            var neighbour = false
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx; val ny = y + dy
                if (nx in 0 until regionWidth && ny in 0 until regionHeight &&
                    foreground[ny * regionWidth + nx]) neighbour = true
            }
            if (!neighbour) coherent[index] = false
        }
        coherent.copyInto(foreground)
        val ink = foreground.count { it }
        // Blank or largely selected rectangles are unsafe. Require that the
        // visible ink has a plausible occupancy within a character region.
        if (ink < 3 || ink * 100L < pixels.size.toLong() ||
            ink * 100L > pixels.size.toLong() * (if (lineBackground == null) 62 else 76)) return null

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

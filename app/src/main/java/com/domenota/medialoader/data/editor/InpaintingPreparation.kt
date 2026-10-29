package com.domenota.medialoader.data.editor

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Square in source pixels; an origin outside the image requests reflected context. */
internal data class InpaintingRegion(val left: Int, val top: Int, val size: Int)
internal data class InpaintingInputs(val image: FloatArray, val mask: FloatArray)

internal object InpaintingPreparation {
    fun region(coverage: IntArray, width: Int, height: Int, maxWorkingSide: Int): InpaintingRegion? {
        require(width > 0 && height > 0 && coverage.size == width * height)
        var left = width; var top = height; var right = -1; var bottom = -1
        for (i in coverage.indices) if (coverage[i] > 0) {
            val x = i % width; val y = i / width
            left = min(left, x); right = max(right, x)
            top = min(top, y); bottom = max(bottom, y)
        }
        if (right < 0) return null
        val extent = max(right - left + 1, bottom - top + 1)
        val desired = max(128, extent + 2 * max(32, extent / 2))
        val side = min(desired, min(max(width, height), max(maxWorkingSide, extent)))
        fun origin(start: Int, end: Int, length: Int): Int =
            if (side > length) (length - side) / 2
            else ((start + end + 1 - side) / 2).coerceIn(0, length - side)
        return InpaintingRegion(origin(left, right, width), origin(top, bottom, height), side)
    }

    /** Keep glyph interiors opaque; feather only OUTSIDE the expanded selection. */
    fun coverage(alpha: IntArray, width: Int, height: Int): IntArray {
        require(width > 0 && height > 0 && alpha.size == width * height)
        val binary = IntArray(alpha.size) { if (alpha[it] > 16) 255 else 0 }
        val core = dilate(binary, width, height, (min(width, height) / 512).coerceIn(1, 3))
        val outer = dilate(core, width, height, 1)
        return IntArray(alpha.size) { if (core[it] > 0) 255 else if (outer[it] > 0) 128 else 0 }
    }

    private fun dilate(input: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val horizontal = IntArray(input.size)
        for (y in 0 until height) for (x in 0 until width) {
            for (dx in max(0, x - radius)..min(width - 1, x + radius)) {
                if (input[y * width + dx] > 0) { horizontal[y * width + x] = 255; break }
            }
        }
        val output = IntArray(input.size)
        for (y in 0 until height) for (x in 0 until width) {
            for (dy in max(0, y - radius)..min(height - 1, y + radius)) {
                if (horizontal[dy * width + x] > 0) { output[y * width + x] = 255; break }
            }
        }
        return output
    }

    fun reflected(index: Int, length: Int): Int {
        require(length > 0)
        val period = length * 2
        val wrapped = ((index % period) + period) % period
        return if (wrapped < length) wrapped else period - 1 - wrapped
    }

    fun inputs(source: IntArray, coverage: IntArray, width: Int, height: Int,
        region: InpaintingRegion, side: Int = 512): InpaintingInputs {
        require(source.size == width * height && coverage.size == source.size && side > 0)
        val plane = side * side
        val image = FloatArray(3 * plane)
        val mask = FloatArray(plane)
        val scale = region.size.toFloat() / side
        for (y in 0 until side) for (x in 0 until side) {
            val sourceX = region.left + (x + 0.5f) * scale - 0.5f
            val sourceY = region.top + (y + 0.5f) * scale - 0.5f
            val x0 = floor(sourceX).toInt(); val y0 = floor(sourceY).toInt()
            val fx = sourceX - x0; val fy = sourceY - y0
            val p00 = source[reflected(y0, height) * width + reflected(x0, width)]
            val p10 = source[reflected(y0, height) * width + reflected(x0 + 1, width)]
            val p01 = source[reflected(y0 + 1, height) * width + reflected(x0, width)]
            val p11 = source[reflected(y0 + 1, height) * width + reflected(x0 + 1, width)]
            val index = y * side + x
            for (channel in 0..2) {
                val shift = (2 - channel) * 8
                fun value(p: Int) = ((p ushr shift) and 255).toFloat()
                image[channel * plane + index] = interpolate(value(p00), value(p10), value(p01), value(p11), fx, fy) / 255f
            }
            // Max-pool coverage over the pixel footprint: distant small marks
            // must not disappear when a large crop is reduced to 512 pixels.
            val xStart = floor(region.left + x * scale).toInt()
            val xEnd = ceil(region.left + (x + 1) * scale).toInt()
            val yStart = floor(region.top + y * scale).toInt()
            val yEnd = ceil(region.top + (y + 1) * scale).toInt()
            scan@ for (sy in yStart until yEnd) for (sx in xStart until xEnd) {
                // Image padding mirrors source pixels, including selected objects.
                // Mirror the mask too so these copies are not treated as known context.
                if (coverage[reflected(sy, height) * width + reflected(sx, width)] > 0) {
                    mask[index] = 1f; break@scan
                }
            }
        }
        return InpaintingInputs(image, mask)
    }

    fun composite(source: IntArray, coverage: IntArray, width: Int, height: Int,
        region: InpaintingRegion, prediction: FloatArray, side: Int = 512): IntArray {
        require(source.size == width * height && coverage.size == source.size && prediction.size == 3 * side * side)
        val output = source.copyOf()
        val plane = side * side
        for (i in output.indices) {
            val alpha = coverage[i]
            if (alpha == 0) continue
            val mx = ((i % width - region.left + 0.5f) * side / region.size - 0.5f).coerceIn(0f, side - 1f)
            val my = ((i / width - region.top + 0.5f) * side / region.size - 0.5f).coerceIn(0f, side - 1f)
            val x0 = floor(mx).toInt(); val y0 = floor(my).toInt()
            val x1 = min(x0 + 1, side - 1); val y1 = min(y0 + 1, side - 1)
            var color = 0xff000000.toInt()
            for (channel in 0..2) {
                val start = channel * plane
                val replacement = interpolate(prediction[start + y0 * side + x0], prediction[start + y0 * side + x1],
                    prediction[start + y1 * side + x0], prediction[start + y1 * side + x1], mx - x0, my - y0).coerceIn(0f, 255f)
                val shift = (2 - channel) * 8
                val original = (source[i] ushr shift) and 255
                val value = ((original * (255 - alpha) + replacement * alpha) / 255f).roundToInt().coerceIn(0, 255)
                color = color or (value shl shift)
            }
            output[i] = color
        }
        return output
    }

    private fun interpolate(a: Float, b: Float, c: Float, d: Float, x: Float, y: Float): Float =
        (a * (1 - x) + b * x) * (1 - y) + (c * (1 - x) + d * x) * y
}

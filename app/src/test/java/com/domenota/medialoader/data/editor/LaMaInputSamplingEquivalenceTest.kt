package com.domenota.medialoader.data.editor

import kotlin.math.ceil
import kotlin.math.floor
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** Verify performance refactor did not change even one model input pixel. */
class LaMaInputSamplingEquivalenceTest {
    private fun baseline(
        source: IntArray, coverage: IntArray, width: Int, height: Int,
        region: InpaintingRegion, side: Int,
    ): InpaintingInputs {
        val plane = side * side
        val image = FloatArray(3 * plane)
        val mask = FloatArray(plane)
        val scale = region.size.toFloat() / side
        for (y in 0 until side) for (x in 0 until side) {
            val sourceX = region.left + (x + 0.5f) * scale - 0.5f
            val sourceY = region.top + (y + 0.5f) * scale - 0.5f
            val x0 = floor(sourceX).toInt()
            val y0 = floor(sourceY).toInt()
            val fx = sourceX - x0
            val fy = sourceY - y0
            val p00 = source[InpaintingPreparation.reflected(y0, height) * width +
                InpaintingPreparation.reflected(x0, width)]
            val p10 = source[InpaintingPreparation.reflected(y0, height) * width +
                InpaintingPreparation.reflected(x0 + 1, width)]
            val p01 = source[InpaintingPreparation.reflected(y0 + 1, height) * width +
                InpaintingPreparation.reflected(x0, width)]
            val p11 = source[InpaintingPreparation.reflected(y0 + 1, height) * width +
                InpaintingPreparation.reflected(x0 + 1, width)]
            val index = y * side + x
            for (channel in 0..2) {
                val shift = (2 - channel) * 8
                fun value(p: Int) = ((p ushr shift) and 255).toFloat()
                val a = value(p00); val b = value(p10)
                val c = value(p01); val d = value(p11)
                val top = a * (1f - fx) + b * fx
                val bottom = c * (1f - fx) + d * fx
                image[channel * plane + index] =
                    (top * (1f - fy) + bottom * fy) / 255f
            }
            val xStart = floor(region.left + x * scale).toInt()
            val xEnd = ceil(region.left + (x + 1) * scale).toInt()
            val yStart = floor(region.top + y * scale).toInt()
            val yEnd = ceil(region.top + (y + 1) * scale).toInt()
            scan@ for (sy in yStart until yEnd) for (sx in xStart until xEnd) {
                if (coverage[InpaintingPreparation.reflected(sy, height) * width +
                        InpaintingPreparation.reflected(sx, width)] > 0) {
                    mask[index] = 1f
                    break@scan
                }
            }
        }
        return InpaintingInputs(image, mask)
    }

    @Test fun optimizedSamplingMatchesOriginalForPortraitLandscapeAndReflectedBounds() {
        val sizes = listOf(17 to 23, 23 to 17, 15 to 15)
        for ((width, height) in sizes) {
            val source = IntArray(width * height) { i ->
                0xff000000.toInt() or ((i * 37 and 255) shl 16) or
                    ((i * 91 and 255) shl 8) or (i * 17 and 255)
            }
            val coverage = IntArray(source.size) { i -> if (i % 13 == 0) 255 else 0 }
            for (region in listOf(
                InpaintingRegion(2, 3, 13),
                InpaintingRegion(-3, -4, 25),
                InpaintingRegion(width - 6, height - 5, 17),
            )) {
                val expected = baseline(source, coverage, width, height, region, 37)
                val actual = InpaintingPreparation.inputs(
                    source, coverage, width, height, region, 37)
                assertArrayEquals(expected.image, actual.image, 0f)
                assertArrayEquals(expected.mask, actual.mask, 0f)
            }
        }
    }
}

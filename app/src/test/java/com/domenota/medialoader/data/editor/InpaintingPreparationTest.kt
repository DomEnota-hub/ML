package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class InpaintingPreparationTest {
    @Test fun smallSelectionUsesLocalSquareContextInsteadOfWholePortrait() {
        val alpha = IntArray(800 * 1200)
        for (y in 590..609) for (x in 390..409) alpha[y * 800 + x] = 255
        val region = requireNotNull(InpaintingPreparation.region(alpha, 800, 1200, 1024))
        assertEquals(128, region.size)
        assertTrue(region.left < 390 && region.left + region.size > 409)
        assertTrue(region.top < 590 && region.top + region.size > 609)
    }

    @Test fun edgeCropContainsAllSelectedPixelsAndEmptySelectionSkipsModel() {
        val alpha = IntArray(80 * 200)
        alpha[0] = 255
        val region = requireNotNull(InpaintingPreparation.region(alpha, 80, 200, 1024))
        assertTrue(region.left <= 0 && region.left + region.size > 0)
        assertTrue(region.top <= 0 && region.top + region.size > 0)
        assertNull(InpaintingPreparation.region(IntArray(16000), 80, 200, 1024))
    }

    @Test fun expansionCoversGlyphFringesAndOnlyOuterRingIsFeathered() {
        val alpha = IntArray(49).also { it[24] = 255 }
        val coverage = InpaintingPreparation.coverage(alpha, 7, 7)
        assertEquals(255, coverage[24])
        assertEquals(255, coverage[23])
        assertEquals(128, coverage[22])
        assertEquals(0, coverage[21])
        assertTrue(InpaintingPreparation.coverage(IntArray(49), 7, 7).all { it == 0 })
    }
    @Test fun reflectedPaddingUsesImageEdgesWithoutStretching() {
        assertEquals(0, InpaintingPreparation.reflected(-1, 3))
        assertEquals(1, InpaintingPreparation.reflected(-2, 3))
        assertEquals(2, InpaintingPreparation.reflected(3, 3))
        assertEquals(1, InpaintingPreparation.reflected(4, 3))
        val source = IntArray(8) { (0xff000000.toInt() or (((it + 1) * 20) shl 16)) }
        val input = InpaintingPreparation.inputs(source, IntArray(8), 4, 2, InpaintingRegion(0, -1, 4), 4)
        assertEquals(20f / 255, input.image[0], 0.0001f)
        assertEquals(20f / 255, input.image[4], 0.0001f)
        assertEquals(100f / 255, input.image[8], 0.0001f)
        assertTrue(input.mask.all { it == 0f })
    }

    @Test fun tinyDistantMarksSurviveDownsampling() {
        val alpha = IntArray(1024 * 1024)
        alpha[100 * 1024 + 100] = 255
        alpha[900 * 1024 + 900] = 255
        val input = InpaintingPreparation.inputs(IntArray(alpha.size), alpha, 1024, 1024,
            InpaintingRegion(0, 0, 1024), 8)
        assertEquals(1f, input.mask[0], 0f)
        assertEquals(1f, input.mask[63], 0f)
        assertEquals(2, input.mask.count { it > 0 })
    }

    @Test fun compositingPreservesUntouchedPixelsIncludingAlpha() {
        val source = IntArray(9) { 0x800000ff.toInt() }
        val coverage = IntArray(9).also { it[4] = 255; it[5] = 128 }
        val prediction = FloatArray(27).also { for (i in 0..8) it[i] = 255f }
        val result = InpaintingPreparation.composite(source, coverage, 3, 3, InpaintingRegion(0, 0, 3), prediction, 3)
        assertEquals(0xffff0000.toInt(), result[4])
        assertEquals(0xff80007f.toInt(), result[5])
        for (i in source.indices) if (coverage[i] == 0) assertEquals(source[i], result[i])
    }

    @Test fun reflectedSelectedPixelsAreNotExposedAsKnownContext() {
        val coverage = IntArray(8).also { it[0] = 255 }
        val input = InpaintingPreparation.inputs(IntArray(8), coverage, 4, 2,
            InpaintingRegion(-1, 0, 4), 4)
        assertEquals(1f, input.mask[0], 0f) // reflected source(0,0)
        assertEquals(1f, input.mask[1], 0f) // actual source(0,0)
        assertEquals(0f, input.mask[2], 0f)
    }

}

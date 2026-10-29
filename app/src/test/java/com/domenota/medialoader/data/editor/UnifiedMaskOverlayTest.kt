package com.domenota.medialoader.data.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedMaskOverlayTest {
    @Test fun overlappingPassesHaveExactlyOneInteriorOpacity() {
        // This array represents the UNION of two fully opaque strokes that cross.
        val width = 15
        val height = 15
        val coverage = IntArray(width * height)
        for (y in 2..12) for (x in 4..10) coverage[y * width + x] = -1
        for (y in 4..10) for (x in 2..12) coverage[y * width + x] = -1
        val result = UnifiedMaskOverlay.colorize(coverage, width, height)
        val alphaAtCrossing = result[7 * width + 7] ushr 24
        val alphaInsideVertical = result[3 * width + 7] ushr 24
        assertEquals(0x57, alphaAtCrossing)
        assertTrue(alphaInsideVertical in 0x57..0xD9)
        assertEquals(0, result[0])
    }

    @Test fun onlyMergedExternalPerimeterIsOutlined() {
        val w = 20
        val h = 12
        val mask = IntArray(w * h)
        for (y in 2..9) for (x in 2..17) mask[y * w + x] = -1
        val result = UnifiedMaskOverlay.colorize(mask, w, h)
        // A cross through the covered interior cannot generate any circular rings.
        assertEquals(0x57, result[5 * w + 10] ushr 24)
        assertEquals(0xD9, result[2 * w + 10] ushr 24)
        assertEquals(0xD9, result[1 * w + 10] ushr 24)
        assertEquals(0, result[0])
    }

    @Test fun invalidDimensionsFailEarly() {
        try {
            UnifiedMaskOverlay.colorize(intArrayOf(0), 2, 2)
            throw AssertionError("Expected size validation")
        } catch (_: IllegalArgumentException) { }
    }
}

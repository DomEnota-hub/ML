package com.domenota.medialoader.data.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Real coordinate and binary-mask regression tests, no downloaded weights required. */
class MobileSamGeometryTest {
    @Test fun longestSide1024WithoutStretching() {
        assertEquals(MobileSamFrame(1024, 512), MobileSamGeometry.frame(2048, 1024))
        assertEquals(MobileSamFrame(512, 1024), MobileSamGeometry.frame(480, 960))
        assertEquals(MobileSamFrame(1024, 1024), MobileSamGeometry.frame(250, 250))
    }

    @Test fun normalizedTapMapsToEncoderResizedPixels() {
        val point = MobileSamGeometry.prompt(
            MaskPoint(0.25f, 0.75f), MobileSamFrame(1024, 512))
        assertEquals(256f, point[0], 0.01f)
        assertEquals(384f, point[1], 0.01f)
    }

    @Test fun segmentOnlyConnectedIslandUnderTap() {
        val width = 64
        val height = 48
        val mask = FloatArray(width * height) { -4f }
        for (y in 10..28) for (x in 5..22) mask[y * width + x] = 3f
        for (y in 4..30) for (x in 36..60) mask[y * width + x] = 3f
        val strokes = MobileSamGeometry.strokesFromLogits(
            mask, width, height, MaskPoint(0.2f, 0.4f))
        assertTrue(strokes.isNotEmpty())
        assertTrue(strokes.all { stroke -> stroke.points.all { it.x < 0.5f } })
        assertTrue(strokes.all { !it.erase })
        assertTrue(strokes.all { it.diameter > 0f })
    }

    @Test fun closeClickMayRecoverFromOnePixelDisplacement() {
        val width = 64
        val height = 64
        val mask = FloatArray(width * height) { -1f }
        for (y in 20..34) for (x in 20..34) mask[y * width + x] = 1f
        val strokes = MobileSamGeometry.strokesFromLogits(
            mask, width, height, MaskPoint(19f / 64, 25f / 64))
        assertTrue(strokes.isNotEmpty())
    }

    @Test fun failClosedForBackgroundNanAndWholePhoto() {
        val width = 40
        val height = 40
        fun rejects(mask: FloatArray, point: MaskPoint) {
            try {
                MobileSamGeometry.strokesFromLogits(mask, width, height, point)
                fail("A blank, corrupt or unsafe mask must never reach LaMa")
            } catch (expected: IOException) { /* correct */ }
        }
        rejects(FloatArray(width * height) { -1f }, MaskPoint(.5f, .5f))
        rejects(FloatArray(width * height) { Float.NaN }, MaskPoint(.5f, .5f))
        rejects(FloatArray(width * height) { 1f }, MaskPoint(.5f, .5f))
    }
}

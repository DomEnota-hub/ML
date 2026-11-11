package com.domenota.medialoader.data.editor

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PersonManualRefinementMaskTest {
    @Test fun manualSubtractionIsAppliedToTheSameMaskUsedForPreview() {
        val person = MaskStroke(listOf(MaskPoint(.2f, .5f), MaskPoint(.8f, .5f)), .15f)
        val subtract = MaskStroke(listOf(MaskPoint(.5f, .4f), MaskPoint(.5f, .6f)),
            .12f, erase = true)
        val combined = listOf(person, subtract)
        val mask = LaMaInpaintingEngine.rasterizeMask(combined, 128, 128)
        try {
            assertTrue(Color.alpha(mask.getPixel(30, 64)) > 10)
            assertEquals(0, Color.alpha(mask.getPixel(64, 64)))
            val coverage = IntArray(128 * 128)
            mask.getPixels(coverage, 0, 128, 0, 0, 128, 128)
            val preview = UnifiedMaskOverlay.colorize(coverage, 128, 128)
            assertTrue(preview[64 * 128 + 30] != 0)
            assertEquals("Manually erased background must have no highlighted fill",
                0, preview[64 * 128 + 64])
        } finally {
            mask.recycle()
        }
    }

    @Test fun manualAdditionIsNotRestrictedToDetectorBoundingBox() {
        val person = MaskStroke(listOf(MaskPoint(.2f, .5f), MaskPoint(.8f, .5f)), .08f)
        val hair = MaskStroke(listOf(MaskPoint(.48f, .24f), MaskPoint(.52f, .2f)), .06f)
        val mask = LaMaInpaintingEngine.rasterizeMask(listOf(person, hair), 128, 128)
        try {
            assertTrue("Hair addition must survive in actual processing mask",
                Color.alpha(mask.getPixel(64, 28)) > 10)
            assertTrue(Color.alpha(mask.getPixel(64, 64)) > 10)
        } finally {
            mask.recycle()
        }
    }
}

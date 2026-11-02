package com.domenota.medialoader.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPreviewFitTest {
    @Test fun portraitNeverExceedsAvailableHeight() {
        val fit = fitVideoPreview(390f, 245f, 1080, 1920)
        assertEquals(137.8125f, fit.width, .01f)
        assertEquals(245f, fit.height, .01f)
    }
    @Test fun wideSquareAndExtremeAspectRatiosStayWithinCanvas() {
        for ((w, h) in listOf(1920 to 1080, 1080 to 1080, 1200 to 5000, 5000 to 1200)) {
            val fit = fitVideoPreview(360f, 212f, w, h)
            assertTrue(fit.width in 0f..360f)
            assertTrue(fit.height in 0f..212f)
            assertEquals(w.toFloat() / h, fit.width / fit.height, .0001f)
        }
    }
    @Test fun invalidSizeDoesNotCreateHugeOverlay() {
        assertEquals(VideoPreviewFit(0f, 0f), fitVideoPreview(0f, 190f, 1080, 1920))
        assertEquals(VideoPreviewFit(0f, 0f), fitVideoPreview(300f, 190f, 0, 1920))
    }
    @Test fun rotationsRemainWithinRendererBounds() {
        assertEquals(-170f, normalizeVideoRotation(190f), .001f)
        assertEquals(170f, normalizeVideoRotation(-190f), .001f)
        assertEquals(0f, normalizeVideoRotation(720f), .001f)
    }
}

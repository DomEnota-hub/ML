package com.domenota.medialoader.ui.screens

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoViewportMathTest {
    @Test fun fitZoomKeepsLetterboxedAxisFixedUntilImageFillsViewport() {
        val limits = photoPanLimits(400f, 800f, imageWidth = 1000, imageHeight = 500, zoom = 2f)
        assertEquals(200f, limits.x, .001f)
        assertEquals(0f, limits.y, .001f)
    }

    @Test fun canPanTallPortraitAfterZoom() {
        val limits = photoPanLimits(400f, 800f, imageWidth = 500, imageHeight = 1200, zoom = 3f)
        assertTrue(limits.x > 0f)
        assertTrue(limits.y > 0f)
        val pan = clampPhotoPan(Offset(10_000f, -10_000f), limits)
        assertEquals(limits.x, pan.x, .001f)
        assertEquals(-limits.y, pan.y, .001f)
    }

    @Test fun noZoomMeansNoPanOrAccidentalBlankSpace() {
        val limits = photoPanLimits(1080f, 2240f, 900, 1200, 1f)
        assertEquals(Offset.Zero, limits)
        assertEquals(Offset.Zero, clampPhotoPan(Offset(-33f, 64f), limits))
    }
}

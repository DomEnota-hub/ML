package com.domenota.medialoader.ui.screens.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CutoutBrushSliderGeometryTest {
    @Test fun thumbRemainsInsideCanvasAcrossWholeRange() {
        for (width in listOf(24f, 40f, 80f, 240f)) {
            val (left, right) = cutoutSliderTrackBounds(width, 11f)
            assertTrue(left >= 0f)
            assertTrue(right <= width)
            assertTrue(left <= right)
            assertEquals(0f, cutoutSliderTouchFraction(left, width, 11f), .0001f)
            assertEquals(1f, cutoutSliderTouchFraction(right, width, 11f), .0001f)
            assertEquals(.5f, cutoutSliderTouchFraction((left + right) / 2f, width, 11f), .0001f)
        }
    }

    @Test fun touchesBeyondEndpointsClampToZeroAndHundredPercent() {
        assertEquals(0f, cutoutSliderTouchFraction(-300f, 200f, 11f), .0001f)
        assertEquals(1f, cutoutSliderTouchFraction(500f, 200f, 11f), .0001f)
    }
}

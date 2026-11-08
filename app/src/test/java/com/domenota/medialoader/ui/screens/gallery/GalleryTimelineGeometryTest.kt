package com.domenota.medialoader.ui.screens.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

/** Track endpoint and thumb share the SAME coordinate system. */
class GalleryTimelineGeometryTest {
    @Test fun roundThumbIsCenteredOnTrackAtEveryProgress() {
        val w = 300f
        val radius = 6f
        assertEquals(6f, galleryTimelineThumbX(0f, w, radius), 0.001f)
        assertEquals(150f, galleryTimelineThumbX(.5f, w, radius), 0.001f)
        assertEquals(294f, galleryTimelineThumbX(1f, w, radius), 0.001f)
    }
    @Test fun coordinateToSeekPositionRoundTripsWithoutOffset() {
        for (f in listOf(0f, .01f, .2f, .5f, .75f, .99f, 1f)) {
            val x = galleryTimelineThumbX(f, 340f, 6f)
            assertEquals(f, galleryTimelineFraction(x, 340f, 6f), .0001f)
        }
    }
    @Test fun touchesOutsideTrackAreClamped() {
        assertEquals(0f, galleryTimelineFraction(-10f, 200f, 6f), 0f)
        assertEquals(1f, galleryTimelineFraction(205f, 200f, 6f), 0f)
    }
}

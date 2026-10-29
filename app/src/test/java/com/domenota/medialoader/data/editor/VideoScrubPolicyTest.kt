package com.domenota.medialoader.data.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoScrubPolicyTest {
    @Test fun movesByFingerDistanceAndStopsAtMediaBounds() {
        val duration = 120_000L
        assertEquals(60_000L, VideoScrubPolicy.target(30_000L, 200f, 400, duration))
        assertEquals(0L, VideoScrubPolicy.target(30_000L, -400f, 400, duration))
        assertEquals(duration, VideoScrubPolicy.target(90_000L, 400f, 400, duration))
    }

    @Test fun shortDragIsPreciseEvenForLongFilms() {
        assertEquals(306_000L, VideoScrubPolicy.target(300_000L, 40f, 400, 7_200_000L))
        assertEquals(450_000L, VideoScrubPolicy.target(300_000L, 200f, 400, 7_200_000L))
    }
}

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
        assertEquals(330_000L, VideoScrubPolicy.target(300_000L, 200f, 400, 7_200_000L))
    }
    @Test fun equalDistanceProducesEqualSignedDeltaAndReversal() {
        for (distance in listOf(0f, 20f, 80f, 160f, 400f)) {
            val forward = VideoScrubPolicy.target(90_000L, distance, 400, 180_000L)
            val backward = VideoScrubPolicy.target(90_000L, -distance, 400, 180_000L)
            assertEquals(forward - 90_000L, 90_000L - backward)
        }
        assertEquals(102_000L, VideoScrubPolicy.target(90_000L, 80f, 400, 180_000L))
        assertEquals(102_000L, VideoScrubPolicy.target(95_000L, 80f, 400, 180_000L) - 5_000L)
        assertEquals(90_000L, VideoScrubPolicy.target(90_000L, 0f, 400, 180_000L))
    }

    @Test fun invalidGeometryAndNonfiniteDistanceDoNotSeek() {
        assertEquals(2000L, VideoScrubPolicy.target(2000L, 10f, 0, 10000L))
        assertEquals(2000L, VideoScrubPolicy.target(2000L, Float.NaN, 400, 10000L))
        assertEquals(0L, VideoScrubPolicy.target(2000L, 10f, 400, 0L))
        assertEquals(10000L, VideoScrubPolicy.target(2000L, 400f, 400, 10000L))
    }
}

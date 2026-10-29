package com.domenota.medialoader.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FloatingVideoGesturePolicyTest {
    @Test fun seekOnlyWhenWindowIsLargeEnoughAndTapIsOnSide() {
        assertNull(FloatingVideoGesturePolicy.seekDelta(279, 1f, 20f))
        assertEquals(-10_000L, FloatingVideoGesturePolicy.seekDelta(280, 1f, 110f))
        assertEquals(10_000L, FloatingVideoGesturePolicy.seekDelta(280, 1f, 170f))
        assertNull(FloatingVideoGesturePolicy.seekDelta(280, 1f, 140f))
        assertNull(FloatingVideoGesturePolicy.seekDelta(558, 2f, 10f))
    }

    @Test fun windowCanReachScreenEdges() {
        assertEquals(1080, FloatingVideoGesturePolicy.maxWidth(1080))
        assertEquals(0, FloatingVideoGesturePolicy.clampX(40, 1080, 1080))
        assertEquals(0, FloatingVideoGesturePolicy.clampX(-30, 700, 1080))
        assertEquals(380, FloatingVideoGesturePolicy.clampX(500, 700, 1080))
    }
}

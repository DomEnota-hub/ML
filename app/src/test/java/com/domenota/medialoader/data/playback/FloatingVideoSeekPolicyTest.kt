package com.domenota.medialoader.data.playback

import org.junit.Assert.*
import org.junit.Test

class FloatingVideoSeekPolicyTest {
    @Test fun mapsWholeWidthAndClampsOutsideWithoutOverflow() {
        assertEquals(0L, FloatingVideoSeekPolicy.target(-30f, 300, 90_000))
        assertEquals(0L, FloatingVideoSeekPolicy.target(0f, 300, 90_000))
        assertEquals(45_000L, FloatingVideoSeekPolicy.target(150f, 300, 90_000))
        assertEquals(90_000L, FloatingVideoSeekPolicy.target(300f, 300, 90_000))
        assertEquals(90_000L, FloatingVideoSeekPolicy.target(500f, 300, 90_000))
        assertEquals(Long.MAX_VALUE, FloatingVideoSeekPolicy.target(300f, 300, Long.MAX_VALUE))
    }
    @Test fun unknownDurationOrInvalidGeometryCannotSeek() {
        assertNull(FloatingVideoSeekPolicy.target(20f, 300, -9223372036854775807L))
        assertNull(FloatingVideoSeekPolicy.target(20f, 300, 0L))
        assertNull(FloatingVideoSeekPolicy.target(20f, 0, 100L))
        assertNull(FloatingVideoSeekPolicy.target(Float.NaN, 300, 100L))
    }
}

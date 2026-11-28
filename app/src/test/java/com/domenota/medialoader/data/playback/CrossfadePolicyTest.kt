package com.domenota.medialoader.data.playback

import org.junit.Assert.*
import org.junit.Test

class CrossfadePolicyTest {
    @Test fun defaultsAreOffAndRangeIsStrict() {
        assertFalse(CrossfadeSettings().enabled)
        assertEquals(5, CrossfadeSettings().seconds)
        assertTrue(runCatching { CrossfadeSettings(true, 0) }.isFailure)
        assertTrue(runCatching { CrossfadeSettings(true, 13) }.isFailure)
    }
    @Test fun durationRespectsSpeedAndShortTracks() {
        assertEquals(5000L, CrossfadePolicy.durationMs(CrossfadeSettings(true,5), 180000, 1f))
        assertEquals(10000L, CrossfadePolicy.durationMs(CrossfadeSettings(true,5), 180000, 2f))
        assertEquals(2000L, CrossfadePolicy.durationMs(CrossfadeSettings(true,12), 4000, 1f))
        assertEquals(0L, CrossfadePolicy.durationMs(CrossfadeSettings(true,12), 1500, 1f))
    }
    @Test fun complementaryGainsAreBounded() {
        assertEquals(1f to 0f, CrossfadePolicy.gains(6000,5000))
        assertEquals(.5f to .5f, CrossfadePolicy.gains(2500,5000))
        assertEquals(0f to 1f, CrossfadePolicy.gains(-1,5000))
        assertEquals(1f to 0f, CrossfadePolicy.gains(0,0))
    }
    @Test fun disabledAndUnknownDurationNeverOverlap() {
        assertEquals(0L, CrossfadePolicy.durationMs(CrossfadeSettings(), 100000, 1f))
        assertEquals(0L, CrossfadePolicy.durationMs(CrossfadeSettings(true), -1, 1f))
        assertEquals(0L, CrossfadePolicy.durationMs(CrossfadeSettings(true), 100000, Float.NaN))
    }
}

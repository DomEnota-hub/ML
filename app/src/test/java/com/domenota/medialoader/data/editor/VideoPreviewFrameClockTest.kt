package com.domenota.medialoader.data.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPreviewFrameClockTest {
    @Test fun cachedPausedFrameKeepsOwnClockWhenDecoderPrefetchesAnotherPeriod() {
        val clock = VideoPreviewFrameClock()
        clock.record(0, 250_000)
        clock.record(33_333, 283_333)
        clock.record(550_000, 0) // A future clip must not change the paused frame's source time.
        assertEquals(250_000L, clock.sourceTimeUs(0))
        assertEquals(283_333L, clock.sourceTimeUs(33_333))
        clock.record(0, 5_250_000) // Seek into a clipped source; retain its source offset.
        assertEquals(5_250_000L, clock.sourceTimeUs(0))
    }
}

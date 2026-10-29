package com.domenota.medialoader.data.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFramePositionTest {
    @Test fun `uses playhead within selected range`() {
        assertEquals(4_200L, VideoFramePosition.clamp(4_200L, 2_000L, 8_000L, 10_000L))
    }

    @Test fun `never requests frame beyond trim end or video duration`() {
        assertEquals(7_999L, VideoFramePosition.clamp(9_000L, 2_000L, 8_000L, 10_000L))
        assertEquals(9_999L, VideoFramePosition.clamp(10_000L, 0L, 10_000L, 10_000L))
    }

    @Test fun `starts from trim start when playhead is before it`() {
        assertEquals(2_000L, VideoFramePosition.clamp(0L, 2_000L, 8_000L, 10_000L))
    }
}

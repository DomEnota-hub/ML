package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class VideoTimelineFrameMathTest {
    private val draft = VideoProjectDraft(listOf(
        VideoClipDraft("a", "content://a", 10000, 1000, 5000),
        VideoClipDraft("b", "content://b", 9000, 2000, 6000),
    ))

    @Test fun seekRoundsToFrameGridInSourceCoordinates() {
        val absolute = draft.timelineStart(0) + 528L
        assertEquals(518L, VideoTimelineFrameMath.projectPosition(draft, absolute, 33L))
        val arbitrary = draft.timelineStart(0) + 543L
        val snapped = VideoTimelineFrameMath.projectPosition(draft, arbitrary, 33L)
        assertEquals(551L, snapped)
        assertEquals(1, draft.locateAt(draft.timelineStart(1)).clipIndex)
    }

    @Test fun unknownFrameRateKeepsMillisecondSeekWithoutInventingFps() {
        assertEquals(1537L, VideoTimelineFrameMath.projectPosition(draft, 1537L, null))
        assertEquals(1537L, VideoTimelineFrameMath.projectPosition(draft, 1537L, 0L))
    }

    @Test fun frameStepsClampToSelectedClipInsteadOfCrossingSegments() {
        val start = VideoTimelineFrameMath.stepSelected(draft, "b", 0L, -1, 40L)
        assertEquals(draft.timelineStart(1), start)
        val end = VideoTimelineFrameMath.stepSelected(draft, "b", 8000L, 1, 40L)
        assertTrue(end >= draft.timelineStart(1))
        assertTrue(end < draft.durationMs)
    }

    @Test fun tinyClipAndTrimmedEdgesAreAlwaysSafe() {
        val short = VideoClipDraft("small", "content://small", 80L)
        assertEquals(66L, VideoTimelineFrameMath.snapSource(short, 100000L, 33L))
        assertEquals(0L, VideoTimelineFrameMath.snapSource(short, -1L, null))
    }

    @Test fun noInvalidDirectionOrFrameIntervalAccepted() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoTimelineFrameMath.stepSelected(draft, "a", 0L, 0, 33L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoTimelineFrameMath.stepSelected(draft, "a", 0L, 1, 0L)
        }
    }
}

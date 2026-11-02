package com.domenota.medialoader.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoClipReorderMathTest {
    @Test fun longDragMovesAcrossEveryCrossedClipNotOnlyOne() {
        val durations = listOf(1000L, 1000L, 1000L, 1000L, 1000L)
        // Tiny clips retain 56dp minimum touch width and 3dp spacing.
        assertEquals(1, VideoClipReorderMath.destinationIndex(durations, 0, 59f, 1f, 1f))
        assertEquals(3, VideoClipReorderMath.destinationIndex(durations, 0, 183f, 1f, 1f))
        assertEquals(4, VideoClipReorderMath.destinationIndex(durations, 0, 1000f, 1f, 1f))
        assertEquals(0, VideoClipReorderMath.destinationIndex(durations, 4, -1000f, 1f, 1f))
    }

    @Test fun zoomAndDensityUseActualStripWidthForCrossing() {
        val durations = listOf(10000L, 10000L, 10000L)
        // A 10s clip at 2x zoom is 300dp; at 1x it is 150dp.
        assertEquals(0, VideoClipReorderMath.destinationIndex(durations, 0, 250f, 2f, 1f))
        assertEquals(1, VideoClipReorderMath.destinationIndex(durations, 0, 303f, 2f, 1f))
        assertEquals(0, VideoClipReorderMath.destinationIndex(durations, 0, 300f, 1f, 2f))
        assertEquals(1, VideoClipReorderMath.destinationIndex(durations, 0, 306f, 1f, 2f))
    }

    @Test fun draggingWithinSameClipDoesNotReorderAndEdgesClamp() {
        val d = listOf(1000L, 9000L)
        assertEquals(0, VideoClipReorderMath.destinationIndex(d, 0, 25f, 1f, 1f))
        assertEquals(1, VideoClipReorderMath.destinationIndex(d, 1, 500f, 1f, 1f))
        assertEquals(0, VideoClipReorderMath.destinationIndex(d, 0, Float.NaN, 1f, 1f))
        assertEquals(0, VideoClipReorderMath.destinationIndex(d, 0, -10000f, 1f, 1f))
    }

    @Test fun trimming20SecondsTo15ShowsShorterClipInsteadOfOld380DpPlateau() {
        val referenceDuration = 20_000L
        val old = VideoClipReorderMath.widthDp(20_000L, 2.5f, referenceDuration)
        val shorter = VideoClipReorderMath.widthDp(15_000L, 2.5f, referenceDuration)
        assertEquals(750f, old, .001f)
        assertEquals(562.5f, shorter, .001f)
        assertTrue(shorter < old)
        assertEquals(15_000f / 20_000f, shorter / old, .001f)
    }

    @Test fun gestureUsesFixedScaleAndSupportsTwoEndsBothDirections() {
        val scale = VideoClipReorderMath.pixelsPerSecondDp(2.5f, 20_000L)
        assertEquals(37.5f, scale, .001f)
        // +75 px with density=2 corresponds to +1 second of output time.
        assertEquals(1000L, VideoClipReorderMath.sourceDeltaMs(75f, scale, 2f, 1f))
        // Negative drag extends the left boundary; 2x speed needs 2s source per 1s output.
        assertEquals(-2000L, VideoClipReorderMath.sourceDeltaMs(-75f, scale, 2f, 2f))
        assertEquals(0L, VideoClipReorderMath.sourceDeltaMs(0f, scale, 2f, 1f))
    }

    @Test fun longVideosFitTimeScaleButRemainProportionalWithoutMaxWidthClipping() {
        val longest = 30L * 60_000L
        assertEquals(960f, VideoClipReorderMath.widthDp(longest, 2.5f, longest), .01f)
        assertEquals(480f, VideoClipReorderMath.widthDp(longest / 2, 2.5f, longest), .01f)
    }
}

package com.domenota.medialoader.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoClipReorderMathTest {
    @Test fun longDragMovesAcrossEveryCrossedClipNotOnlyOne() {
        val durations = listOf(1000L, 1000L, 1000L, 1000L, 1000L)
        // All strips have the minimum 76 dp width and a 3 dp gap.
        assertEquals(1, VideoClipReorderMath.destinationIndex(durations, 0, 79f, 1f, 1f))
        assertEquals(3, VideoClipReorderMath.destinationIndex(durations, 0, 245f, 1f, 1f))
        assertEquals(4, VideoClipReorderMath.destinationIndex(durations, 0, 1000f, 1f, 1f))
        assertEquals(0, VideoClipReorderMath.destinationIndex(durations, 4, -1000f, 1f, 1f))
    }

    @Test fun zoomAndDensityUseActualStripWidthForCrossing() {
        val durations = listOf(10000L, 10000L, 10000L)
        assertEquals(0, VideoClipReorderMath.destinationIndex(durations, 0, 250f, 2f, 1f))
        assertEquals(1, VideoClipReorderMath.destinationIndex(durations, 0, 303f, 2f, 1f))
        assertEquals(0, VideoClipReorderMath.destinationIndex(durations, 0, 300f, 1f, 2f))
        assertEquals(1, VideoClipReorderMath.destinationIndex(durations, 0, 306f, 1f, 2f))
    }

    @Test fun draggingWithinTheSameClipDoesNotReorderAndEdgesClamp() {
        val d = listOf(1000L, 9000L)
        assertEquals(0, VideoClipReorderMath.destinationIndex(d, 0, 25f, 1f, 1f))
        assertEquals(1, VideoClipReorderMath.destinationIndex(d, 1, 500f, 1f, 1f))
        assertEquals(0, VideoClipReorderMath.destinationIndex(d, 0, Float.NaN, 1f, 1f))
        assertEquals(0, VideoClipReorderMath.destinationIndex(d, 0, -10000f, 1f, 1f))
    }
}

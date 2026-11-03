package com.domenota.medialoader.ui.screens

import org.junit.Assert.*
import org.junit.Test

class VideoTrimViewportMathTest {
    @Test fun expandingPreviouslyTrimmedSourceKeepsEitherTouchedGripUnderFinger() {
        for (end in listOf(false, true)) {
            val kept = 375f; val full = 750f
            val shift = VideoClipReorderMath.trimExpansionScrollDp(kept, full, 20000, 5000, 15000, end)
            val normal = VideoClipReorderMath.trimGrips(VideoClipReorderMath.TrimSelection(0f, kept), kept)
            val expanded = VideoClipReorderMath.trimGrips(VideoClipReorderMath.trimSelection(20000, 5000, 15000, full), full)
            assertEquals(if (end) normal.rightDp else normal.leftDp,
                (if (end) expanded.rightDp else expanded.leftDp) - shift, .01f)
        }
    }
    @Test fun gripsFollowSmallMovementsWithoutAHandleWidthDeadZone() {
        val full = VideoClipReorderMath.trimGrips(VideoClipReorderMath.TrimSelection(0f, 750f), 750f)
        val left = VideoClipReorderMath.trimGrips(VideoClipReorderMath.TrimSelection(5f, 750f), 750f)
        val right = VideoClipReorderMath.trimGrips(VideoClipReorderMath.TrimSelection(0f, 745f), 750f)
        assertEquals(5f, left.leftDp - full.leftDp, .01f)
        assertEquals(-5f, right.rightDp - full.rightDp, .01f)
    }
    @Test fun minimumWidthShortClipStillAnchorsItsGrip() {
        val delta = VideoClipReorderMath.trimExpansionScrollDp(56f, 56f, 200, 0, 100, true)
        assertTrue(delta < 0f)
        assertTrue(delta.isFinite())
    }
}

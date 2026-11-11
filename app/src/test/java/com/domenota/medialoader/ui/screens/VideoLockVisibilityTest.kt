package com.domenota.medialoader.ui.screens

import org.junit.Assert.*
import org.junit.Test

class VideoLockVisibilityTest {
    @Test fun unlockedTouchTargetHidesImmediatelyOnFirstOutsideTapAndReturnsOnSecond() {
        assertFalse(VideoLockVisibility.afterCanvasTap(true))
        assertTrue(VideoLockVisibility.afterCanvasTap(false))
    }

    @Test fun repeatedTapsAlternateVisibilityWithoutChangingLockState() {
        var visible = true
        val locked = true
        visible = VideoLockVisibility.afterCanvasTap(visible)
        assertFalse(visible)
        assertTrue(locked)
        visible = VideoLockVisibility.afterCanvasTap(visible)
        assertTrue(visible)
        assertTrue(locked)
    }
}

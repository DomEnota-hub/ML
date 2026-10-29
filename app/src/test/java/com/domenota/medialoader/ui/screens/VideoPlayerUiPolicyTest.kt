package com.domenota.medialoader.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlayerUiPolicyTest {
    @Test fun nativeControllerOwnsTheThreeSecondTimeout() {
        assertTrue(VideoPlayerUiPolicy.NATIVE_CONTROLLER_TIMEOUT_MS == 3_000)
    }

    @Test fun playingVideoHidesVisibleControlsAfterTimeout() {
        assertTrue(VideoPlayerUiPolicy.shouldScheduleHide(
            isPlaying = true, chromeVisible = true, controlsLocked = false))
        assertFalse(VideoPlayerUiPolicy.shouldScheduleHide(
            isPlaying = false, chromeVisible = true, controlsLocked = false))
        assertFalse(VideoPlayerUiPolicy.shouldScheduleHide(
            isPlaying = true, chromeVisible = false, controlsLocked = false))
    }

    @Test fun endedVideoMustRestartFromBeginning() {
        assertTrue(VideoPlayerUiPolicy.shouldRestartFromBeginning(playbackEnded = true))
        assertFalse(VideoPlayerUiPolicy.shouldRestartFromBeginning(playbackEnded = false))
    }
}

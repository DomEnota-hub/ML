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
    
    @Test fun previousRestartsPastThreeSecondsButOpensPreviousNearBeginning() {
        val policy = VideoPlayerUiPolicy
        assertTrue(policy.previousAction(3001L, true) == VideoPlayerUiPolicy.PreviousAction.RESTART)
        assertTrue(policy.previousAction(150_000L, true) == VideoPlayerUiPolicy.PreviousAction.RESTART)
        assertTrue(policy.previousAction(3000L, true) == VideoPlayerUiPolicy.PreviousAction.OPEN_PREVIOUS)
        assertTrue(policy.previousAction(0L, true) == VideoPlayerUiPolicy.PreviousAction.OPEN_PREVIOUS)
        assertTrue(policy.previousAction(2999L, false) == VideoPlayerUiPolicy.PreviousAction.RESTART)
        assertTrue(policy.previousAction(-1L, true) == VideoPlayerUiPolicy.PreviousAction.RESTART)
    }

    @Test fun adjacentVideoFollowsCurrentAlbumOrderSkippingPhotosWithoutWrapping() {
        val video = com.domenota.medialoader.core.model.MediaType.VIDEO
        val photo = com.domenota.medialoader.core.model.MediaType.PHOTO
        val items = listOf(video, photo, video, video, photo, video)
        val policy = VideoPlayerUiPolicy
        org.junit.Assert.assertEquals(2, policy.adjacentVideoIndex(items, 0, 1))
        org.junit.Assert.assertEquals(0, policy.adjacentVideoIndex(items, 2, -1))
        org.junit.Assert.assertEquals(3, policy.adjacentVideoIndex(items, 2, 1))
        org.junit.Assert.assertEquals(5, policy.adjacentVideoIndex(items, 3, 1))
        org.junit.Assert.assertNull(policy.adjacentVideoIndex(items, 0, -1))
        org.junit.Assert.assertNull(policy.adjacentVideoIndex(items, 5, 1))
        org.junit.Assert.assertNull(policy.adjacentVideoIndex(items, 99, -1))
        org.junit.Assert.assertNull(policy.adjacentVideoIndex(items, 0, 0))
    }
}

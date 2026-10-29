package com.domenota.medialoader.core.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeExtractionChoiceTest {
    private fun info(height: Int, audio: Boolean, extra: List<StreamFormat> = emptyList()) =
        StreamInfo("video", "Video", null, null, null,
            listOf(StreamFormat("video", height, true, audio, "mp4", null, null)) + extra)

    @Test fun publicHighQualityCanCompleteAnalysisWithoutSecondRequest() {
        assertTrue(YouTubeExtractionChoice.publicResultIsEnough(info(1080, true)))
        assertFalse(YouTubeExtractionChoice.publicResultIsEnough(info(720, true)))
        assertFalse(YouTubeExtractionChoice.publicResultIsEnough(info(360, true)))
        assertFalse(YouTubeExtractionChoice.publicResultIsEnough(info(1080, false)))
    }

    @Test fun public720pCanLimitEnhancementWaitWithoutDroppingAudio() {
        assertTrue(YouTubeExtractionChoice.publicResultCanBoundWait(info(720, true)))
        assertFalse(YouTubeExtractionChoice.publicResultCanBoundWait(info(360, true)))
        assertFalse(YouTubeExtractionChoice.publicResultCanBoundWait(info(720, false)))
    }

    @Test fun croppedFullHdStreamCanCompleteAnalysisWithoutSecondRequest() {
        val cropped = StreamInfo("video", "Video", null, null, null,
            listOf(StreamFormat("video", 1012, true, true, "mp4", null, null, width = 1920)))
        assertTrue(YouTubeExtractionChoice.publicResultIsEnough(cropped))
    }

    @Test fun choosesConfirmedHigherPlayableQuality() {
        assertTrue(YouTubeExtractionChoice.preferPublic(info(1080, true), info(338, true)))
        assertFalse(YouTubeExtractionChoice.preferPublic(info(360, true), info(1080, true)))
        assertFalse(YouTubeExtractionChoice.preferPublic(info(1080, false), info(338, true)))
    }

    @Test fun ignoresNonVideoHeights() {
        val storyboard = StreamFormat("storyboard", 2160, false, false, "mhtml", null, null)
        assertEquals(360, YouTubeExtractionChoice.maxVideoHeight(info(360, true, listOf(storyboard))))
    }
}

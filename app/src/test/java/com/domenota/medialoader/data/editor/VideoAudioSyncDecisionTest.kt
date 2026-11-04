package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class VideoAudioSyncDecisionTest {
    @Test fun stalledDecoderIsNotRepeatedlySoughtButUserSeekIsImmediate() {
        assertFalse(VideoAudioSyncDecision.shouldSeek(false, false, true, 2000, 2000))
        assertFalse(VideoAudioSyncDecision.shouldSeek(false, true, true, 400, 75))
        assertTrue(VideoAudioSyncDecision.shouldSeek(false, true, true, 400, 750))
        assertTrue(VideoAudioSyncDecision.shouldSeek(true, false, true, 2000, 75))
    }
    @Test fun pausedFrameUsesTighterPositionWithoutPlayingCooldown() {
        assertTrue(VideoAudioSyncDecision.shouldSeek(false, true, false, 30, 75))
        assertFalse(VideoAudioSyncDecision.shouldSeek(false, true, true, 30, 750))
    }

    @Test fun initialPositionDoesNotFlushDecoderAgainButRealScrubDoes() {
        assertFalse(VideoAudioSyncDecision.shouldSeek(true, false, true, 0, 0))
        assertTrue(VideoAudioSyncDecision.shouldSeek(true, false, true, -1, 0))
    }

    @Test fun decoderMustPlayContinuouslyBeforeAnotherDriftCorrection() {
        // A long prepare does not count as useful playback after a seek.
        assertFalse(VideoAudioSyncDecision.shouldSeek(false, true, true, -900, 1500, 75))
        assertTrue(VideoAudioSyncDecision.shouldSeek(false, true, true, -900, 2250, 750))
        // A user seek still applies immediately during buffering or settling.
        assertTrue(VideoAudioSyncDecision.shouldSeek(true, false, true, -900, 75, 0))
    }

    @Test fun offsetAndSourceTrimUseHalfOpenProjectBounds() {
        val track = VideoAudioTrackDraft("audio", "file:///clip.mp4", 2000, 12000,
            inMs = 3000, outMs = 9000, speed = 2f)
        assertNull(VideoAudioSyncDecision.sourcePositionMs(track, 1999))
        assertEquals(3000L, VideoAudioSyncDecision.sourcePositionMs(track, 2000))
        assertEquals(4500L, VideoAudioSyncDecision.sourcePositionMs(track, 2750))
        assertEquals(8998L, VideoAudioSyncDecision.sourcePositionMs(track, 4999))
        assertNull(VideoAudioSyncDecision.sourcePositionMs(track, 5000))
    }

    @Test fun advancingOffsetTrackDoesNotSeekButMovingItOrReenteringDoes() {
        val track = VideoAudioTrackDraft("audio", "file:///clip.mp4", 2000, 12000,
            inMs = 3000, outMs = 9000, speed = 2f)
        assertFalse(VideoAudioSyncDecision.isJump(track, 2750, track, 2675,
            wasAudible = true, playing = true, wasPlaying = true, elapsedMs = 75))
        assertTrue(VideoAudioSyncDecision.isJump(track.copy(atMs = 2100), 2750, track, 2675,
            wasAudible = true, playing = true, wasPlaying = true, elapsedMs = 75))
        assertTrue(VideoAudioSyncDecision.isJump(track, 2000, track, 1999,
            wasAudible = false, playing = true, wasPlaying = true, elapsedMs = 75))
        assertTrue(VideoAudioSyncDecision.isJump(track.withRange(inMs = 3500), 2750, track, 2675,
            wasAudible = true, playing = true, wasPlaying = true, elapsedMs = 75))
    }
}

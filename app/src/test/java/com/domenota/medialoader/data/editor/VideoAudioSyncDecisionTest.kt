package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class VideoAudioSyncDecisionTest {
    @Test fun decoderStartupLagDoesNotCreatePeriodicAudioFlushes() {
        // A 225 ms decoder startup delay survives every hard seek to a moving video.
        // Repeating the seek after each cooldown would audibly interrupt playback.
        for (time in 750L..6000L step 75L) {
            assertFalse("Unexpected decoder flush at $time ms",
                VideoAudioSyncDecision.shouldSeek(false, true, true, -225, time, time))
        }
    }

    @Test fun videoBufferingHoldsAudioWithoutPausedCorrectionButScrubStillWorks() {
        assertFalse(VideoAudioSyncDecision.shouldSeek(false, true, false, 75, 2000,
            masterBuffering = true))
        assertTrue(VideoAudioSyncDecision.shouldSeek(true, true, false, -3000, 75,
            masterBuffering = true))
    }

    @Test fun steadyTempoRecoversStartupLagAcrossTrackSpeeds() {
        for (base in listOf(.25f, 1f, 2f, 4f)) {
            var drift = -225.0
            var previousSpeed = base
            var tempoChanges = 0
            for (sample in 0..160) {
                val speed = VideoAudioSyncDecision.playbackSpeed(base, (drift * base).toLong(), true, previousSpeed)
                if (speed != previousSpeed) tempoChanges++
                previousSpeed = speed
                assertTrue(speed in base * .95f..base * 1.05f)
                assertFalse(VideoAudioSyncDecision.shouldSeek(false, true, true,
                    (drift * base).toLong(), 2000, 2000, trackSpeed = base))
                drift += (speed / base - 1f) * 75
            }
            assertTrue("Audio processors should not drain on every sample", tempoChanges <= 2)
            assertTrue("Residual lag at speed $base: $drift", kotlin.math.abs(drift) < 50)
            assertEquals(base, VideoAudioSyncDecision.playbackSpeed(base, 0, true), .0001f)
            assertEquals(base, VideoAudioSyncDecision.playbackSpeed(base, -225, false), .0001f)
            assertTrue(VideoAudioSyncDecision.playbackSpeed(base, (225 * base).toLong(), true) < base)
        }
    }

    @Test fun positionNoiseDoesNotToggleTempoAtEverySample() {
        val speeds = listOf(-100L, -41L, -39L, -41L, -39L, -19L, -41L, -39L)
            .runningFold(1f) { previous, drift ->
                VideoAudioSyncDecision.playbackSpeed(1f, drift, true, previous)
            }
        assertEquals(listOf(1f, 1.05f, 1.05f, 1.05f, 1.05f, 1.05f, 1f, 1f, 1f), speeds)
    }

    @Test fun stalledDecoderIsNotRepeatedlySoughtButUserSeekIsImmediate() {
        assertFalse(VideoAudioSyncDecision.shouldSeek(false, false, true, 2000, 2000))
        assertFalse(VideoAudioSyncDecision.shouldSeek(false, true, true, 400, 75))
        assertFalse(VideoAudioSyncDecision.shouldSeek(false, true, true, 400, 750))
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
        assertTrue(VideoAudioSyncDecision.shouldSeek(false, true, true, -2000, 2250, 750))
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

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
}

package com.domenota.medialoader.data.playback

import com.domenota.medialoader.data.playback.alignment.PcmTimeline
import org.junit.Assert.*
import org.junit.Test

class LyricsPcmTimelineTest {
    @Test fun continuousPtsAllowOnlyCodecRoundingAndRejectGapsOverlapsAndRateChanges() {
        val valid=PcmTimeline();valid.accept(0,16000,16000);valid.accept(1000100,8000,16000);valid.accept(1500000,8000,16000)
        for(next in listOf(1040000L,960000L)) {
            val timeline=PcmTimeline();timeline.accept(0,16000,16000)
            assertTrue(runCatching{timeline.accept(next,16000,16000)}.isFailure)
        }
        val changed=PcmTimeline();changed.accept(0,16000,16000)
        assertTrue(runCatching{changed.accept(1000000,44100,44100)}.isFailure)
        assertTrue(runCatching{PcmTimeline().accept(100000,16000,16000)}.isFailure)
    }
}

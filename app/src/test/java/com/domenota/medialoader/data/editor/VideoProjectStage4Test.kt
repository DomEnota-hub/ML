package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class VideoProjectStage4Test {
    private fun project() = VideoProjectDraft.single("video", "content://v/1", 10000L)
    private fun track(id: String = "music") = VideoAudioTrackDraft(id,
        "content://music/$id", atMs = 2000L, sourceDurationMs = 9000L)

    @Test fun audioOverlaysDoNotTouchVideoCutsOrSources() {
        val initial = project()
        val edited = initial.addAudioTrack(track())
            .editAudioTrack("music") { it.copy(inMs = 2000L, outMs = 6000L,
                volume = .5f, fadeInMs = 1000L, fadeOutMs = 1000L) }
        assertEquals(initial.clips, edited.clips)
        assertEquals(1, edited.audioTracks.size)
        assertEquals(4000L, edited.audioTracks.single().durationMs)
        assertEquals(2000L, edited.audioTracks.single().atMs)
        assertEquals(0.5f, edited.audioTracks.single().volume, 0f)
        assertTrue(initial.audioTracks.isEmpty())
        assertTrue(edited.removeAudioTrack("music").audioTracks.isEmpty())
    }

    @Test fun multipleTracksCanOverlapAtDifferentTimelinePositions() {
        val p = project()
            .addAudioTrack(track("one"))
            .addAudioTrack(track("two").copy(atMs = 5000L))
        assertEquals(2, p.audioTracks.size)
        assertEquals(2, p.visibleAudioTracks().size)
        assertEquals(8000L, p.visibleAudioTracks()[0].durationMs)
        assertEquals(5000L, p.visibleAudioTracks()[1].durationMs)
    }

    @Test fun volumeAndFadesFormExpectedEnvelope() {
        val t = track().copy(inMs = 1000L, outMs = 7000L,
            volume = .8f, fadeInMs = 1000L, fadeOutMs = 2000L)
        assertEquals(0f, t.gainAt(1999L), 0.001f)
        assertEquals(0f, t.gainAt(2000L), 0.001f)
        assertEquals(.4f, t.gainAt(2500L), 0.001f)
        assertEquals(.8f, t.gainAt(4000L), 0.001f)
        assertEquals(.4f, t.gainAt(7000L), 0.001f)
        assertEquals(0f, t.gainAt(8000L), 0.001f)
    }

    @Test fun clipsBeyondProjectAreExcludedAndTrailingMusicTrimmed() {
        val p = project().addAudioTrack(track("late").copy(atMs = 11000L))
            .addAudioTrack(track("near-end").copy(atMs = 9500L,
                fadeInMs = 2000L, fadeOutMs = 1000L))
        val visible = p.visibleAudioTracks()
        assertEquals(1, visible.size)
        assertEquals("near-end", visible.single().id)
        assertEquals(500L, visible.single().durationMs)
        assertEquals(500L,
            visible.single().fadeInMs + visible.single().fadeOutMs)
    }

    @Test fun fadesCannotExceedTrimmedAudioLength() {
        assertThrows(IllegalArgumentException::class.java) {
            track().copy(inMs = 0L, outMs = 1000L, fadeInMs = 700L, fadeOutMs = 700L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            track().copy(volume = 1.1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            track().copy(inMs = 3000L, outMs = 2000L)
        }
    }

    @Test fun audioTrackLimitAndIdentityAreEnforced() {
        val filled = (0 until VideoProjectDraft.MAX_AUDIO_TRACKS).fold(project()) { p, i ->
            p.addAudioTrack(track("track-$i"))
        }
        assertEquals(VideoProjectDraft.MAX_AUDIO_TRACKS, filled.audioTracks.size)
        assertThrows(IllegalArgumentException::class.java) {
            filled.addAudioTrack(track("extra"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            project().addAudioTrack(track()).addAudioTrack(track())
        }
    }
}

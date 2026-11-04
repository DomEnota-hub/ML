package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class OriginalAudioSynchronizationTest {
    private fun project() = VideoOriginalAudioDetach.detach(VideoProjectDraft(listOf(
        VideoClipDraft("clip", "file:///video.mp4", 30000L))), setOf("clip")).project
    @Test fun repeatedVideoTrimAndRestoreKeepOriginalAudioOnTheSameSourceFrames() {
        var p = project()
        for ((start, end) in listOf(3000L to 26000L, 7000L to 23000L, 0L to 30000L)) {
            p = p.trimClip("clip", start, end)
            val audio = p.audioTracks.single()
            assertEquals(start, audio.inMs); assertEquals(end, audio.outMs)
            assertEquals(0L, audio.atMs); assertEquals(p.durationMs, audio.durationMs)
        }
    }
    @Test fun manualAudioTrimStaysIndependent() {
        val base = project()
        val edited = base.editAudioTrack(base.audioTracks.single().id) { it.withRange(inMs = 1000) }
            .trimClip("clip", 3000, 26000)
        assertEquals(1000L, edited.audioTracks.single().inMs)
        assertEquals(30000L, edited.audioTracks.single().outMs)
    }
    @Test fun speedChangeKeepsUntouchedOriginalAudioAligned() {
        val p = project().setSpeed("clip", 2f)
        assertEquals(2f, p.audioTracks.single().speed, 0f)
        assertEquals(p.durationMs, p.audioTracks.single().durationMs)
    }
}

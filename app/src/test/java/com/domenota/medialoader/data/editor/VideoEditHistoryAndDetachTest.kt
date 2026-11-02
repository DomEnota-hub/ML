package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class VideoEditHistoryAndDetachTest {
    @Test fun undoRedoPreservesSnapshotsClearsFutureAndLimitsMemory() {
        val history = VideoEditHistory<String>(3)
        history.record("a")
        assertFalse(history.canUndo)
        history.record("b")
        history.record("c")
        history.record("d")
        history.record("e")
        assertEquals("d", history.undo())
        assertEquals("c", history.undo())
        assertEquals("b", history.undo())
        assertNull(history.undo())
        assertEquals("c", history.redo())
        history.record("new")
        assertFalse(history.canRedo)
        assertEquals("c", history.undo())
        assertEquals("new", history.redo())
    }

    private fun video(id: String, start: Long, end: Long, speed: Float = 1f) =
        VideoClipDraft(id = id, sourceUri = "content://media/$id",
            sourceDurationMs = 20000L, inMs = start, outMs = end, speed = speed)

    @Test fun detachTrimmedClipsMaintainsSourceRangesAndProjectOffsets() {
        val project = VideoProjectDraft(listOf(
            video("a", 1000L, 5500L), video("b", 2000L, 9000L)))
        val result = VideoOriginalAudioDetach.detach(project, setOf("a", "b"))
        assertEquals(2, result.project.audioTracks.size)
        val (first, second) = result.project.audioTracks
        assertEquals(0L, first.atMs)
        assertEquals(4500L, second.atMs)
        assertEquals(1000L, first.inMs)
        assertEquals(5500L, first.outMs)
        assertEquals(2000L, second.inMs)
        assertEquals(9000L, second.outMs)
        assertEquals(result.project.audioTracks.map { it.id }, result.newTrackIds)
        assertEquals(project.clips, result.project.clips) // video untouched
    }

    @Test fun cannotDetachWithoutAudioOrWithUnsupportedSpeed() {
        val project = VideoProjectDraft(listOf(video("fast", 0L, 5000L, speed = 2f)))
        try {
            VideoOriginalAudioDetach.detach(project, setOf("fast"))
            fail("Speed mismatch would desynchronize video and audio")
        } catch (_: IllegalArgumentException) { }
        try {
            VideoOriginalAudioDetach.detach(project, emptySet())
            fail("No audio must not silently mute video")
        } catch (_: IllegalArgumentException) { }
    }
}

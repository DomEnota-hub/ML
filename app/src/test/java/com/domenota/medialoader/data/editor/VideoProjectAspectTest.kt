package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class VideoProjectAspectTest {
    @Test fun referenceRatiosKeepSourceForOriginalAndRespectRequestedResolution() {
        val portrait = VideoExportDimensions(1080, 1920)
        assertEquals(portrait, VideoProjectAspect.ORIGINAL.dimensions(portrait))
        assertEquals(VideoExportDimensions(1920, 1080), VideoProjectAspect.LANDSCAPE_16_9.dimensions(portrait))
        assertEquals(VideoExportDimensions(720, 720), VideoProjectAspect.SQUARE.dimensions(portrait, 720))
        assertEquals(listOf("Исходное", "1:1", "3:4", "4:3", "16:9", "9:16", "2.39:1", "2.55:1"),
            VideoProjectAspect.entries.map { it.label })
        for (aspect in VideoProjectAspect.entries.filter { it.ratio != null }) {
            val size = aspect.dimensions(portrait)
            assertEquals(0, size.width % 2); assertEquals(0, size.height % 2)
            assertEquals(requireNotNull(aspect.ratio), size.width.toFloat() / size.height, .01f)
        }
    }
    @Test fun clipEditsAndUndoPreserveCanvasChoice() {
        val project = VideoProjectDraft(listOf(VideoClipDraft("clip", "file:///synthetic.mp4", 20_000)),
            aspect = VideoProjectAspect.CINEMA_239)
        val history = VideoEditHistory<VideoProjectDraft>()
        history.record(project)
        val edited = project.trimClip("clip", 0, 15_000)
        history.record(edited)
        assertEquals(VideoProjectAspect.CINEMA_239, edited.aspect)
        assertEquals(project, history.undo()); assertEquals(edited, history.redo())
    }
}

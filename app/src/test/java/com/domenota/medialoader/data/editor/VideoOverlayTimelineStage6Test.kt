package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class VideoOverlayTimelineStage6Test {
    private val project = VideoProjectDraft(listOf(
        VideoClipDraft("slow", "content://video/slow", 8000L,
            inMs = 1000L, outMs = 5000L, speed = .5f),
        VideoClipDraft("fast", "content://video/fast", 9000L,
            inMs = 2000L, outMs = 8000L, speed = 2f),
    ))
    @Test fun textCrossingClipBoundaryUsesEachSourcesTimeScale() {
        assertEquals(11000L, project.durationMs)
        val layer = TextLayer(id = "title", text = "Hello",
            startMs = 6000L, endMs = 9000L)
        val slow = VideoOverlayTimeline.forClip(project, 0, listOf(layer), emptyList())
        assertEquals(1000L, slow.timeOffsetMs)
        assertEquals(3000L, slow.texts.single().startMs)
        assertEquals(4000L, slow.texts.single().endMs)
        val fast = VideoOverlayTimeline.forClip(project, 1, listOf(layer), emptyList())
        assertEquals(2000L, fast.timeOffsetMs)
        assertEquals(0L, fast.texts.single().startMs)
        assertEquals(2000L, fast.texts.single().endMs)
    }

    @Test fun graphicAtBoundaryAppearsOnlyOnFollowingClip() {
        val sticker = VideoGraphicLayer(id = "star", startMs = 8000L,
            endMs = 10000L)
        val a = VideoOverlayTimeline.forClip(project, 0, emptyList(), listOf(sticker))
        val b = VideoOverlayTimeline.forClip(project, 1, emptyList(), listOf(sticker))
        assertTrue(a.graphics.isEmpty())
        assertEquals(0L, b.graphics.single().startMs)
        assertEquals(4000L, b.graphics.single().endMs)
    }

    @Test fun openEndedOverlayIsClippedByFinalProjectDuration() {
        val title = TextLayer(text = "End", startMs = 10500L)
        val overlays = VideoOverlayTimeline.forClip(project, 1, listOf(title), emptyList())
        assertEquals(5000L, overlays.texts.single().startMs)
        assertEquals(6000L, overlays.texts.single().endMs)
        assertNull(title.endMs)
    }

    @Test fun independentPositionAndGraphicsStyleAreValidated() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoGraphicLayer(kind = VideoGraphicKind.IMAGE, sourceUri = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoGraphicLayer(centerX = 1.1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoGraphicLayer(opacity = Float.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoGraphicLayer(size = -.2f)
        }
        val image = VideoGraphicLayer(kind = VideoGraphicKind.IMAGE,
            sourceUri = "content://photos/123", centerX = .22f, centerY = .75f)
        assertFalse(image.visibleAt(-1L))
        assertTrue(image.visibleAt(0L))
        assertNull(image.within(0L))
        assertEquals(.22f, image.centerX, 0f)
    }

    @Test fun multiClipReorderingOnlyChangesTimelineIntervalsNotOriginalLayers() {
        val original = TextLayer(id = "a", text = "Title", startMs = 2000L,
            endMs = 7000L)
        val moved = project.move("fast", 0)
        val segment = VideoOverlayTimeline.forClip(moved, 0, listOf(original), emptyList())
        assertEquals(3000L, moved.timelineStart(1))
        assertEquals(4000L, segment.texts.single().startMs)
        assertEquals(6000L, segment.texts.single().endMs)
        assertEquals(2000L, original.startMs)
        assertEquals(7000L, original.endMs)
    }
}

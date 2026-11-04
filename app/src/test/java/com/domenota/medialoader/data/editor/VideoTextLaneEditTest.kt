package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class VideoTextLaneEditTest {
    @Test fun edgesAreReversibleAndCannotCrossProjectOrEachOther() {
        val original = TextLayer(text = "Текст", startMs = 1000, endMs = 5000)
        val right = VideoTextLaneEdit.apply(original, VideoTextLaneHandle.END, 3000, 6000)
        assertEquals(3000L, right.endMs)
        assertEquals(original, VideoTextLaneEdit.apply(original, VideoTextLaneHandle.END, 5000, 6000))
        assertEquals(1100L, VideoTextLaneEdit.apply(original, VideoTextLaneHandle.END, 0, 6000).endMs)
        assertEquals(4900L, VideoTextLaneEdit.apply(original, VideoTextLaneHandle.START, 6000, 6000).startMs)
        val moved = VideoTextLaneEdit.apply(original, VideoTextLaneHandle.MOVE, 5900, 6000)
        assertEquals(2000L, moved.startMs); assertEquals(6000L, moved.endMs)
    }
    @Test fun tinyProjectAndIndependentCopyKeepValidIntervalsAndStyle() {
        val original = TextLayer(text = "A", startMs = 0, endMs = 40, bold = true, rotation = 25f, opacity = .4f)
        val tiny = VideoTextLaneEdit.apply(original, VideoTextLaneHandle.START, 1000, 40)
        assertEquals(0L, tiny.startMs); assertEquals(40L, tiny.endMs)
        val copy = VideoTextLaneEdit.duplicate(original)
        assertNotEquals(original.id, copy.id)
        assertEquals(original.text, copy.text); assertEquals(original.endMs, copy.endMs)
        assertEquals(original.rotation, copy.rotation); assertEquals(original.opacity, copy.opacity)
        assertTrue(copy.bold); assertEquals("A", original.text)
    }
}

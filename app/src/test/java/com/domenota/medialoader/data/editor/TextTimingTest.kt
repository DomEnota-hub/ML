package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class TextTimingTest {
    @Test fun movingStartAndEndPreservesNonemptyClipRelativeInterval() {
        val layer = TextLayer(startMs = 2000, endMs = 5000)
        assertEquals(4999L, TextTiming.startAt(layer, 8000, 10_000).startMs)
        assertEquals(2001L, TextTiming.endAt(layer, 0, 10_000).endMs)
        assertEquals(10_000L, TextTiming.endAt(layer, 12_000, 10_000).endMs)
        assertEquals(0L, TextTiming.startAt(layer, -1, 10_000).startMs)
    }
    @Test fun durationEndSeeksToLastVisibleMillisecond() {
        assertEquals(9999L, TextTiming.seekPosition(10_000, 10_000))
        assertEquals(0L, TextTiming.seekPosition(-100, 10_000))
        assertEquals(0L, TextTiming.seekPosition(0, 1))
    }
}

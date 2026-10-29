package com.domenota.medialoader.data.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionWindowPlannerTest {
    private val window = 12_000L

    @Test fun initialRecognitionUsesMiddleTwelveSeconds() {
        assertEquals(84_000L, RecognitionWindowPlanner.startMs(180_000L, window, 0))
    }

    @Test fun retriesExploreDifferentPartsOfLongAudio() {
        val initial = RecognitionWindowPlanner.startMs(180_000L, window, 0)!!
        val offsets = (1..4).map { RecognitionWindowPlanner.startMs(180_000L, window, it)!! }
        assertEquals(4, offsets.distinct().size)
        offsets.forEach { assertNotEquals(initial, it) }
        assertTrue(offsets.all { it >= 0L && it + window <= 180_000L })
    }

    @Test fun tooShortClipDoesNotInventNewMaterial() {
        assertEquals(0L, RecognitionWindowPlanner.startMs(10_000L, window, 0))
        assertNull(RecognitionWindowPlanner.startMs(10_000L, window, 1))
        assertNull(RecognitionWindowPlanner.startMs(16_000L, window, 1))
    }

    @Test fun unknownDurationCanOnlyUseOriginalWindow() {
        assertEquals(0L, RecognitionWindowPlanner.startMs(null, window, 0))
        assertNull(RecognitionWindowPlanner.startMs(null, window, 2))
    }
}

package com.domenota.medialoader.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeekFeedbackStateTest {
    @Test fun repeatedSeekInSameDirectionStartsNewFeedbackEvent() {
        val first = SeekFeedbackState().show(-10_000L)
        val second = first.show(-10_000L)

        assertEquals("−10 с", first.message)
        assertEquals("−10 с", second.message)
        assertEquals(first.revision + 1, second.revision)
        assertEquals(second, second.clearIfCurrent(first.revision))
        assertNull(second.clearIfCurrent(second.revision).message)
    }
}

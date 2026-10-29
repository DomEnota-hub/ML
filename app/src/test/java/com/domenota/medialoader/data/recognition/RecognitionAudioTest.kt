package com.domenota.medialoader.data.recognition

import com.domenota.medialoader.core.recognition.RecognitionAudioSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionAudioTest {
    @Test fun clipsShorterThanFourSecondsAreRejected() {
        assertFalse(RecognitionAudio.eligible(ShortArray(RecognitionAudioSpec.MIN_SAMPLES - 1)))
        assertTrue(RecognitionAudio.eligible(ShortArray(RecognitionAudioSpec.MIN_SAMPLES)))
    }

    @Test fun longTracksUseTheMiddleTwelveSeconds() {
        val extra = RecognitionAudioSpec.SAMPLE_RATE_HZ * 2
        val source = ShortArray(RecognitionAudioSpec.TARGET_SAMPLES + extra) { it.toShort() }
        val window = RecognitionAudio.directWindow(source)

        assertEquals(RecognitionAudioSpec.TARGET_SAMPLES, window.size)
        val expectedStart = extra / 2
        assertEquals(source[expectedStart], window.first())
        assertEquals(source[expectedStart + window.lastIndex], window.last())
    }

    @Test fun shortLoopRepeatsWithoutCreatingAnotherAudioFile() {
        val source = shortArrayOf(10, 20, 30, 40)
        val looped = RecognitionAudio.loopToTarget(source)

        assertEquals(RecognitionAudioSpec.TARGET_SAMPLES, looped.size)
        assertArrayEquals(shortArrayOf(10, 20, 30, 40, 10, 20, 30, 40), looped.copyOfRange(0, 8))
    }

    @Test fun confirmationLoopStartsHalfACycleLater() {
        val source = shortArrayOf(10, 20, 30, 40, 50, 60)
        val phase = RecognitionAudio.confirmationPhase(source)
        val shifted = RecognitionAudio.loopToTarget(source, phase)

        assertEquals(3, phase)
        assertArrayEquals(shortArrayOf(40, 50, 60, 10, 20, 30), shifted.copyOfRange(0, 6))
    }
}

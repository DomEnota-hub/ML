package com.domenota.medialoader.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioWaveformShapeTest {
    @Test
    fun `quiet passages remain shorter than loud passages after shaping`() {
        val input = floatArrayOf(0f, 0.08f, 0.09f, 0.11f, 0.2f, 0.25f)
        val shaped = shapeWaveform(input)
        assertEquals(0f, shaped[0], 0f)
        assertTrue(shaped[1] < shaped[4])
        assertTrue(shaped[4] <= shaped[5])
        assertTrue(shaped[5] <= 1f)
    }
}

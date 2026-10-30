package com.domenota.medialoader.ui.components

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformBackgroundRenderingTest {
    @Test fun denseColumnsKeepHighestAmplitudeAndDoNotInventUniformHeight() {
        val peaks = listOf(0.05f, 0.20f, 0.8f, 0.1f, 0.3f, 0.9f, 0.02f, 0.6f)
        assertArrayEquals(floatArrayOf(0.13625f, 0.5025f, 0.645f, 0.3535f),
            waveformColumnPeaks(peaks, 4), 0.0001f)
    }

    @Test fun drawingMoreColumnsKeepsDistinctSoundDynamics() {
        val peaks = listOf(0.05f, 0.8f, 0.15f, 0.9f)
        assertArrayEquals(peaks.toFloatArray(), waveformColumnPeaks(peaks, 4), 0f)
        assertArrayEquals(floatArrayOf(0.48125f, 0.58125f), waveformColumnPeaks(peaks, 2), 0.0001f)
    }

    @Test fun calibratedRmsPreservesDynamicsAndAvoidsSaturatedFullHeight() {
        val rms = floatArrayOf(0.0f, 0.12f, 0.2f, 0.35f, 0.4f)
        val shaped = normalizeWaveformRms(rms)
        assertEquals(0f, shaped[0], 0f)
        assertTrue(shaped[1] < shaped[2])
        assertTrue(shaped[2] < shaped[3])
        assertTrue(shaped[3] < shaped[4])
        assertTrue(shaped[4] <= 0.92f)
        assertTrue(shaped[1] < 0.40f)
    }

    @Test fun shortSharpSpikesAreSoftenedButNotErased() {
        val calibrated = normalizeWaveformRms(floatArrayOf(0.1f, 0.1f, 0.6f, 0.1f, 0.1f))
        assertTrue(calibrated[2] > calibrated[1])
        assertTrue(calibrated[2] < 0.92f)
        assertTrue(calibrated[0] < calibrated[2])
    }

    @Test fun pcmEnergyRepresentsAverageLoudnessNotOnlyAbsolutePeak() {
        val data = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        data.putShort(0)
        data.putShort(16384)
        data.putShort(-16384)
        val energy = pcmEnergy(data, 0, 6, AudioFormat.ENCODING_PCM_16BIT)
        assertEquals(3L, energy.count)
        assertEquals(0.5, energy.total, 0.0001)
    }

    @Test fun revisedTrackAndBucketCountNeverReuseObsoleteCache() {
        val base = waveformCacheIdentity("content://media/audio/42", 180000L, 1000L, 100L, 1024)
        assertEquals(base, waveformCacheIdentity("content://media/audio/42", 180000L, 1000L, 100L, 1024))
        assertNotEquals(base, waveformCacheIdentity("content://media/audio/42", 180000L, 1000L, 101L, 1024))
        assertNotEquals(base, waveformCacheIdentity("content://media/audio/42", 180000L, 1001L, 100L, 1024))
        assertNotEquals(base, waveformCacheIdentity("content://media/audio/42", 180000L, 1000L, 100L, 120))
    }
}

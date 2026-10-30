package com.domenota.medialoader.ui.components

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WaveformBackgroundRenderingTest {
    @Test fun denseColumnsKeepHighestAmplitudeAndDoNotInventUniformHeight() {
        val peaks = listOf(0.05f, 0.20f, 0.8f, 0.1f, 0.3f, 0.9f, 0.02f, 0.6f)
        assertArrayEquals(floatArrayOf(0.2f, 0.8f, 0.9f, 0.6f),
            waveformColumnPeaks(peaks, 4), 0.0001f)
    }

    @Test fun drawingMoreColumnsKeepsDistinctSoundDynamics() {
        val peaks = listOf(0.05f, 0.8f, 0.15f, 0.9f)
        assertArrayEquals(peaks.toFloatArray(), waveformColumnPeaks(peaks, 4), 0f)
        assertArrayEquals(floatArrayOf(0.8f, 0.9f), waveformColumnPeaks(peaks, 2), 0f)
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

package com.domenota.medialoader.data.editor

import android.media.AudioFormat
import com.domenota.medialoader.ui.components.AudioEditorViewport
import com.domenota.medialoader.ui.components.accumulateEditorPcm
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class AudioEditorViewportTest {
    @Test fun zoomCentersOnPlaybackAndClampsAtTrackEdges() {
        val span = AudioEditorViewport.span(160_000L, 8)
        assertEquals(20_000L, span)
        assertEquals(70_000L, AudioEditorViewport.centeredStart(80_000L, 160_000L, span))
        assertEquals(0L, AudioEditorViewport.centeredStart(5L, 160_000L, span))
        assertEquals(140_000L, AudioEditorViewport.centeredStart(159_999L, 160_000L, span))
        assertEquals(32, AudioEditorViewport.maxZoom(160_000L))
        assertEquals(1, AudioEditorViewport.maxZoom(1_000L))
        assertEquals(4, AudioEditorViewport.maxZoom(5_000L))
    }

    @Test fun panAndTrimCoordinatesUseAbsoluteTimeWithoutMovingSelection() {
        val start = 70_000L
        val span = 20_000L
        assertEquals(80_000L, AudioEditorViewport.timeAt(100f, 200f, start, span, 160_000L))
        assertEquals(100f, AudioEditorViewport.xFor(80_000L, 200f, start, span), .001f)
        assertEquals(60_000L, AudioEditorViewport.pan(start, 100f, 200f, span, 160_000L))
        assertEquals(140_000L, AudioEditorViewport.pan(start, -10_000f, 200f, span, 160_000L))
        assertTrue(AudioEditorViewport.xFor(5_000L, 200f, start, span) < 0f)
        assertTrue(AudioEditorViewport.xFor(150_000L, 200f, start, span) > 200f)
    }

    @Test fun detailedPcmSeparatesStereoFramesWithinOneDecoderBuffer() {
        val pcm = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        listOf(0, 0, 16384, -16384, 8192, -8192, 0, 0).forEach { pcm.putShort(it.toShort()) }
        val energy = DoubleArray(4)
        val counts = LongArray(4)
        accumulateEditorPcm(pcm, 0, 16, AudioFormat.ENCODING_PCM_16BIT, 0L, 4_000L, 1000, 2, energy, counts)
        assertArrayEquals(longArrayOf(2, 2, 2, 2), counts)
        assertArrayEquals(doubleArrayOf(0.0, 0.5, 0.125, 0.0), energy, .0001)
    }

    @Test fun waveformViewportUsesRealBucketsAndBoundedDetail() {
        val peaks = (0..7).map { it / 8f }
        assertEquals(peaks.subList(2, 4), AudioEditorViewport.visiblePeaks(peaks, 8_000L, 2_000L, 4_000L))
        assertEquals(peaks, AudioEditorViewport.visiblePeaks(peaks, 8_000L, 0L, 8_000L))
        assertEquals(32768, AudioEditorViewport.detailBuckets(500_000L))
        assertEquals(1024, AudioEditorViewport.detailBuckets(100L))
    }
}

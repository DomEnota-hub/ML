package com.domenota.medialoader.data.editor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VideoReversePlanTest {
    @Test fun reversesSegmentsInChronologicalOppositeOrder() {
        assertEquals(listOf(ReverseSegment(4000L, 1001L), ReverseSegment(2000L, 2000L),
            ReverseSegment(0L, 2000L)), reverseSegments(5001L, 2000L))
    }

    @Test fun tinyTerminalRemainderMergesIntoPreviousSegment() {
        assertEquals(listOf(ReverseSegment(2000L, 2001L), ReverseSegment(0L, 2000L)),
            reverseSegments(4001L, 2000L))
    }

    @Test fun shortVideoIsSingleReversedSegment() {
        assertEquals(listOf(ReverseSegment(0L, 700L)), reverseSegments(700L, 2000L))
    }

    @Test fun refusesZeroDurationAndSize() {
        assertThrows(IllegalArgumentException::class.java) { reverseSegments(0L, 2000L) }
        assertThrows(IllegalArgumentException::class.java) { reverseSegments(100L, 0L) }
    }

    @Test fun reverseAudioIsLosslessBeforeSingleFinalAacEncoding() {
        val args = reverseAudioPcmArgs(File("/tmp/source.mp4"),
            ReverseSegment(2000, 1000), File("/tmp/part.wav"))
        assertEquals("pcm_s16le", args[args.indexOf("-c:a") + 1])
        assertEquals("aresample=48000,areverse,asetpts=PTS-STARTPTS", args[args.indexOf("-af") + 1])
        assertEquals("2.000", args[args.indexOf("-ss") + 1])
        assertEquals("1.000", args[args.indexOf("-t") + 1])
    }

    @Test fun higherResolutionUsesSmallerSegments() {
        assertEquals(500L, reverseChunkMillis(3840, 2160))
        assertEquals(1000L, reverseChunkMillis(1920, 1080))
        assertEquals(2000L, reverseChunkMillis(1280, 720))
        assertEquals(3000L, reverseChunkMillis(640, 480))
    }
}

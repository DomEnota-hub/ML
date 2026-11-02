package com.domenota.medialoader.data.editor

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class VideoAudioRetimingTest {
    @Test fun independentAudioUsesOutputTimeForFadesAndSourceTimeForSeek() {
        val track = VideoAudioTrackDraft("a", "file:///audio.mp3", 1000, 20_000,
            inMs = 2000, outMs = 12_000, fadeInMs = 1000, fadeOutMs = 1000, speed = 2f)
        assertEquals(5000L, track.durationMs)
        assertEquals(4000L, track.sourceAt(1000))
        assertEquals(.5f, track.gainAt(1500), .001f)
        assertEquals(.5f, track.gainAt(5500), .001f)
        val project = VideoProjectDraft(listOf(VideoClipDraft("v", "file:///v.mp4", 4000)),
            audioTracks = listOf(track))
        val clipped = project.visibleAudioTracks().single()
        assertEquals(3000L, clipped.durationMs)
        assertEquals(8000L, clipped.outMs)
        assertEquals(4000L, clipped.endAtMs)
    }
    @Test fun speedChangesClampFadeSumAndAtTempoSupportsEntireRange() {
        val base = VideoAudioTrackDraft("a", "file:///a.mp3", 0, 4000,
            fadeInMs = 2000, fadeOutMs = 2000)
        val fast = base.withRange(speed = 4f)
        assertEquals(1000L, fast.durationMs)
        assertEquals(1000L, fast.fadeInMs + fast.fadeOutMs)
        for (speed in listOf(.25f, .5f, 1f, 2f, 4f)) {
            val track = base.withRange(speed = speed)
            val args = VideoAudioMixCommand.build(File("video.mp4"), listOf(File("audio.mp3")),
                listOf(track), File("out.mp4"), false, track.durationMs)
            val graph = args[args.indexOf("-filter_complex") + 1]
            assertTrue(graph.contains("atrim="))
            if (speed != 1f) assertTrue("speed $speed must reach FFmpeg", graph.contains("atempo="))
            assertTrue(args.contains("copy")) // Video must not be encoded by the audio-only tier.
        }
    }
}

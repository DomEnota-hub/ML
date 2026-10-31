package com.domenota.medialoader.data.editor

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class VideoAudioMixCommandTest {
    private val video = File("/test/video.mp4")
    private val output = File("/test/result.mp4")
    private fun track(id: String, at: Long, duration: Long = 5000L) =
        VideoAudioTrackDraft(id, "content://music/$id", at, sourceDurationMs = duration)

    @Test fun twoOverlaysAreMixedWithOriginalSoundAndCopiedVideo() {
        val tracks = listOf(
            track("one", 0).copy(volume = .6f, fadeInMs = 500L, fadeOutMs = 1500L),
            track("two", 4000).copy(inMs = 1000L, outMs = 4000L),
        )
        val args = VideoAudioMixCommand.build(video,
            listOf(File("/test/one.mp3"), File("/test/two.aac")),
            tracks, output, includeOriginalAudio = true, projectDurationMs = 12000L)
        val filter = args[args.indexOf("-filter_complex") + 1]
        assertTrue(filter.contains("[0:a:0]aresample=48000"))
        assertTrue(filter.contains("volume=0.600"))
        assertTrue(filter.contains("afade=t=in:st=0:d=0.500"))
        assertTrue(filter.contains("afade=t=out:st=3.500:d=1.500"))
        assertTrue(filter.contains("atrim=start=1.000:end=4.000"))
        assertTrue(filter.contains("adelay=4000:all=1"))
        assertTrue(filter.contains("amix=inputs=3"))
        assertEquals("copy", args[args.indexOf("-c:v") + 1])
        assertEquals("aac", args[args.indexOf("-c:a") + 1])
        assertEquals("12.000", args[args.indexOf("-t") + 1])
    }

    @Test fun originalMuteKeepsExternalMusic() {
        val args = VideoAudioMixCommand.build(video, listOf(File("/test/one.mp3")),
            listOf(track("one", 1000)), output, includeOriginalAudio = false,
            projectDurationMs = 12000L)
        val filter = args[args.indexOf("-filter_complex") + 1]
        assertFalse(filter.contains("[0:a:0]"))
        assertTrue(filter.contains("adelay=1000:all=1"))
        assertTrue(filter.endsWith("[a1]anull[aout]"))
        assertEquals("[aout]", args[args.indexOf("-map") + 3])
    }

    @Test fun audioCannotExtendBeyondProjectAndInputsMustMatch() {
        val a = track("long", 9000)
        assertThrows(IllegalArgumentException::class.java) {
            VideoAudioMixCommand.build(video, listOf(File("/test/a.mp3")),
                listOf(a), output, true, 10000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoAudioMixCommand.build(video, emptyList(),
                listOf(a), output, true, 10000L)
        }
    }
}

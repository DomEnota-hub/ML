package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class VideoAudioLaneGeometryTest {
    @Test fun musicPlacementUsesActualWidthsOfShortAndLongVideoItems() {
        val project = VideoProjectDraft(listOf(VideoClipDraft("short", "file:///a", 100),
            VideoClipDraft("long", "file:///b", 20_000), VideoClipDraft("last", "file:///c", 1000)))
        val scale = VideoAudioLaneScale(project, listOf(64f, 750f, 64f))
        assertEquals(67f, scale.xAt(100), .001f)
        for (time in listOf(0L, 50, 100, 1100, 20_000, 20_100, 21_100))
            assertEquals(time.toDouble(), scale.timeAt(scale.xAt(time)).toDouble(), 1.0)
        assertEquals(0L, scale.timeAt(-100f))
        assertEquals(project.durationMs, scale.timeAt(10_000f))
    }
    @Test fun audioHandlesAreReversibleAtQuarterAndQuadrupleSpeed() {
        for (speed in listOf(.25f, 4f)) {
            val original = VideoAudioTrackDraft("a", "file:///music", 2000, 20_000,
                inMs = 4000, outMs = 12_000, speed = speed, fadeInMs = 100, fadeOutMs = 100)
            val changed = VideoAudioLaneEdit.apply(original, VideoAudioLaneHandle.START, 2500, 60_000)
            assertEquals(4000 + (500 * speed).toLong(), changed.inMs)
            assertEquals(2500L, changed.atMs)
            assertEquals(original.atMs + original.durationMs, changed.atMs + changed.durationMs)
            assertEquals(original, VideoAudioLaneEdit.apply(original, VideoAudioLaneHandle.START, 2000, 60_000))
            val extended = VideoAudioLaneEdit.apply(original, VideoAudioLaneHandle.END,
                original.atMs + original.durationMs + 500, 60_000)
            assertEquals(12_000 + (500 * speed).toLong(), extended.outMs)
            assertEquals(original, VideoAudioLaneEdit.apply(original, VideoAudioLaneHandle.END,
                original.atMs + original.durationMs, 60_000))
        }
    }
}

package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class VideoProjectDraftTest {
    @Test fun startsWithOneClipAndMapsItsSourcePosition() {
        val project = VideoProjectDraft.single("clip", "content://video/1", 12000)
        assertEquals(12000, project.durationMs)
        assertEquals(VideoTimelineLocation(0, "clip", 5000, 5000), project.locateAt(5000))
        assertEquals(0, project.locateAt(-50).clipIndex)
        assertEquals(12000, project.locateAt(90000).absoluteSourceMs)
    }

    @Test fun timeMappingAtClipBoundariesUsesNextClip() {
        val project = VideoProjectDraft(listOf(
            VideoClipDraft("a", "content://a", 10000, 1000, 4000),
            VideoClipDraft("b", "content://b", 9000, 2000, 6000),
        ))
        assertEquals(7000, project.durationMs)
        assertEquals(VideoTimelineLocation(0, "a", 2999, 3999), project.locateAt(2999))
        assertEquals(VideoTimelineLocation(1, "b", 0, 2000), project.locateAt(3000))
        assertEquals(VideoTimelineLocation(1, "b", 4000, 6000), project.locateAt(7000))
    }

    @Test fun trimmingPreservesOtherClipsAndSourceBoundaries() {
        val original = VideoProjectDraft(listOf(
            VideoClipDraft("first", "content://first", 10000),
            VideoClipDraft("second", "content://second", 5000),
        ))
        val trimmed = original.trimClip("first", 2000, 7000)
        assertEquals(10000, trimmed.durationMs)
        assertEquals(15000, original.durationMs)
        assertEquals(original.clips[1], trimmed.clips[1])
        assertEquals(2000, trimmed.clips[0].inMs)
    }

    @Test fun acceptsIndependentAudioTracksWithoutMutatingVideoSource() {
        val original = VideoProjectDraft.single("id", "content://video", 10000)
        val newProject = original.copy(audioTracks = listOf(
            VideoAudioTrackDraft("music", "content://audio", 500, 4000)))
        assertEquals(original.clips, newProject.clips)
        assertEquals(1, newProject.audioTracks.size)
        assertTrue(original.audioTracks.isEmpty())
    }

    @Test fun rejectsDuplicatesAndInvalidTimeRanges() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoClipDraft("a", "content://video", 10000, 8000, 8000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoProjectDraft(listOf(
                VideoClipDraft("same", "content://1", 1000),
                VideoClipDraft("same", "content://2", 2000)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoAudioTrackDraft("music", "content://audio", -1, 1000)
        }
    }
}

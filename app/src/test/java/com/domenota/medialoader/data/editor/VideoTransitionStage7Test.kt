package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class VideoTransitionStage7Test {
    private fun project() = VideoProjectDraft(listOf(
        VideoClipDraft("slow", "content://a", 9000L,
            inMs = 1000L, outMs = 5000L, speed = .5f),
        VideoClipDraft("fast", "content://b", 10000L,
            inMs = 2000L, outMs = 8000L, speed = 2f),
        VideoClipDraft("tail", "content://c", 3000L),
    ))

    @Test fun transitionsNeverChangeTotalProjectDurationOrSourceTrim() {
        val original = project()
        assertEquals(14000L, original.durationMs)
        val edited = original.setTransitionAfter("slow",
            VideoTransitionDraft(VideoTransitionType.DIP_BLACK, 800L))
            .setTransitionAfter("fast",
                VideoTransitionDraft(VideoTransitionType.FLASH, 1200L))
        assertEquals(original.durationMs, edited.durationMs)
        assertEquals(original.audioTracks, edited.audioTracks)
        assertEquals(original.clips.map { it.inMs to it.outMs },
            edited.clips.map { it.inMs to it.outMs })
        assertEquals(2, VideoTransitionTiming.enabledCuts(edited))
    }

    @Test fun halfTransitionIsMeasuredOnEachSideWithOwnSpeed() {
        val edited = project().setTransitionAfter("slow",
            VideoTransitionDraft(VideoTransitionType.DIP_BLACK, 800L))
        val left = VideoTransitionTiming.forClip(edited, 0).single()
        val right = VideoTransitionTiming.forClip(edited, 1).single()
        assertFalse(left.incoming)
        assertTrue(right.incoming)
        // Left clip: 8s at 0.5x => 400ms output equals 200ms of original.
        assertEquals(3800L, left.sourceStartMs)
        assertEquals(4000L, left.sourceEndMs)
        // Right clip: 3s at 2x => 400ms output equals 800ms of original.
        assertEquals(0L, right.sourceStartMs)
        assertEquals(800L, right.sourceEndMs)
        assertEquals(1f, left.coverage(4000L), 0f)
        assertEquals(1f, right.coverage(0L), 0f)
        assertEquals(.5f, right.coverage(400L), .001f)
        assertEquals(0f, right.coverage(800L), 0f)
    }

    @Test fun overlappingIncomingAndOutgoingTransitionsRemainIndependent() {
        val edited = project()
            .setTransitionAfter("slow", VideoTransitionDraft(VideoTransitionType.DIP_BLACK))
            .setTransitionAfter("fast", VideoTransitionDraft(VideoTransitionType.FLASH))
        val phases = VideoTransitionTiming.forClip(edited, 1)
        assertEquals(2, phases.size)
        assertTrue(phases.first().incoming)
        assertFalse(phases.last().incoming)
        assertTrue(phases.first().sourceEndMs <= phases.last().sourceStartMs)
    }

    @Test fun veryShortClipsClampTransitionToAvailableSourceFrames() {
        val p = VideoProjectDraft(listOf(
            VideoClipDraft("a", "content://a", 100L),
            VideoClipDraft("b", "content://b", 125L),
        )).setTransitionAfter("a",
            VideoTransitionDraft(VideoTransitionType.WIPE_BLACK, 3000L))
        val left = VideoTransitionTiming.forClip(p, 0).single()
        val right = VideoTransitionTiming.forClip(p, 1).single()
        assertEquals(50L, left.sourceEndMs - left.sourceStartMs)
        assertEquals(50L, right.sourceEndMs - right.sourceStartMs)
        assertEquals(225L, p.durationMs)
    }

    @Test fun transitionOwnershipAndLastClipSafety() {
        val p = project()
        assertThrows(IllegalArgumentException::class.java) {
            p.setTransitionAfter("tail",
                VideoTransitionDraft(VideoTransitionType.DIP_WHITE))
        }
        assertThrows(IllegalArgumentException::class.java) {
            p.setTransitionAfter("unknown",
                VideoTransitionDraft(VideoTransitionType.DIP_WHITE))
        }
        assertTrue(VideoTransitionTiming.forClip(p, 0).isEmpty())
        assertEquals(0, VideoTransitionTiming.enabledCuts(p))
        val moved = p.setTransitionAfter("fast",
            VideoTransitionDraft(VideoTransitionType.FLASH)).move("fast", 0)
        assertEquals(VideoTransitionType.FLASH, moved.clips.first().transitionOut.type)
    }

    @Test fun visualFxNeverChangeSoundOrAdjacentClipSettings() {
        val sound = VideoAudioTrackDraft("song", "content://song", 1000L, 6000L)
        val p = project().copy(audioTracks = listOf(sound))
        val updated = p.setSpecialFx("fast",
            VideoSpecialFxDraft(VideoSpecialFxType.VIGNETTE, .75f))
        assertEquals(p.durationMs, updated.durationMs)
        assertEquals(p.audioTracks, updated.audioTracks)
        assertFalse(updated.clips[0].specialFx.enabled)
        assertTrue(updated.clips[1].specialFx.enabled)
        assertFalse(updated.clips[2].specialFx.enabled)
        assertThrows(IllegalArgumentException::class.java) {
            VideoSpecialFxDraft(VideoSpecialFxType.PULSE, Float.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoTransitionDraft(durationMs = 99L)
        }
    }
}

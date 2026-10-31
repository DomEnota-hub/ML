package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class VideoProjectStage3Test {
    private fun clip(id: String = "a") = VideoClipDraft(id, "content://video/$id", 12000)

    @Test fun speedConvertsSourceToTimelineAndBack() {
        val fast = clip().copy(inMs = 1000, outMs = 9000, speed = 2f)
        assertEquals(8000L, fast.sourceSpanMs)
        assertEquals(4000L, fast.durationMs)
        assertEquals(1000L, fast.sourceAt(0))
        assertEquals(3000L, fast.sourceAt(1000))
        assertEquals(9000L, fast.sourceAt(4000))
        assertEquals(1000L, fast.outputAt(3000))
        val slow = fast.copy(speed = .5f)
        assertEquals(16000L, slow.durationMs)
        assertEquals(3000L, slow.sourceAt(4000))
        assertEquals(4000L, slow.outputAt(3000))
    }

    @Test fun boundariesAreCalculatedFromRenderedDurations() {
        val p = VideoProjectDraft(listOf(
            clip("a").copy(speed = 2f), clip("b").copy(speed = .5f)))
        assertEquals(30000L, p.durationMs)
        assertEquals(6000L, p.timelineStart(1))
        assertEquals("a", p.locateAt(5999L).clipId)
        assertEquals("b", p.locateAt(6000L).clipId)
        assertEquals(2500L, p.locateAt(11000L).absoluteSourceMs)
        assertEquals(11000L, p.timelinePosition(1, p.clips[1].outputAt(2500L)))
    }

    @Test fun speedAndGeometryStayWithOriginalThroughSplitCopyMoveTrim() {
        val adjusted = clip().copy(speed = 1.5f,
            crop = CropFractions(left = .1f, right = .2f),
            cropPreset = CropAspectPreset.RATIO_16_9,
            rotationDegrees = 90, mirrorHorizontal = true)
        val p = VideoProjectDraft(listOf(adjusted))
            .trimClip("a", 1500, 10500)
            .splitAt("a", 6000, "b")
            .duplicate("b", "c")
            .move("c", 0)
        assertEquals(listOf("c", "a", "b"), p.clips.map { it.id })
        assertTrue(p.clips.all { it.speed == 1.5f && it.mirrorHorizontal })
        assertTrue(p.clips.all { it.rotationDegrees == 90 && it.crop.left == .1f })
        assertEquals(9000L, p.durationMs)
    }

    @Test fun selectedClipGeometryDoesNotAlterNeighbourOrSource() {
        val p = VideoProjectDraft(listOf(clip("a"), clip("b")))
            .setGeometry("a", CropFractions(top = .25f), CropAspectPreset.RATIO_1_1,
                270, true, true)
            .setSpeed("b", .25f)
        assertEquals(12000L, p.clips[0].sourceDurationMs)
        assertEquals(12000L, p.clips[0].durationMs)
        assertTrue(p.clips[0].mirrorHorizontal && p.clips[0].mirrorVertical)
        assertEquals(CropFractions(), p.clips[1].crop)
        assertEquals(48000L, p.clips[1].durationMs)
        assertEquals(1f, p.clips[0].speed)
    }

    @Test fun invalidSpeedAndGeometryAreRejected() {
        val p = VideoProjectDraft.single("a", "content://video/a", 10000)
        for (invalid in listOf(0f, .2f, 5f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                p.setSpeed("a", invalid)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            p.setGeometry("a", CropFractions(left = .8f, right = .7f),
                CropAspectPreset.FREE, 0, false, false)
        }
    }

    @Test fun frameSnapAndSteppingRemainInSourceCoordinates() {
        val p = VideoProjectDraft(listOf(
            clip("a").copy(inMs = 1000, outMs = 9000, speed = 2f)))
        assertEquals(1000L, VideoTimelineFrameMath.projectPosition(p, 1000, 40))
        assertEquals(1020L, VideoTimelineFrameMath.stepSelected(p, "a", 1000, 1, 40))
    }
}

package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class VideoColorGradeStage5Test {
    private fun clip() = VideoClipDraft("first", "content://videos/1", 8000L)
    @Test fun noFilterAndNoCorrectionLeavesFrameUnchanged() {
        val neutral = VideoColorGrade()
        assertTrue(neutral.isNeutral)
        assertTrue(neutral.resolved().isNeutral)
        assertEquals(VideoColorAdjustments(), neutral.resolved())
    }

    @Test fun zeroStrengthDisablesPresetButKeepsManualAdjustment() {
        val preset = VideoColorGrade(preset = VideoFilterPreset.MONO, intensity = 0f)
        assertTrue(preset.isNeutral)
        assertEquals(VideoColorAdjustments(), preset.resolved())
        val manual = preset.copy(manual = VideoColorAdjustments(brightness = .15f))
        assertFalse(manual.isNeutral)
        assertEquals(.15f, manual.resolved().brightness, .0001f)
        assertEquals(0f, manual.resolved().saturation, 0f)
    }

    @Test fun filterStrengthInterpolateInsteadOfOverwritingManualValues() {
        val grade = VideoColorGrade(
            preset = VideoFilterPreset.MONO, intensity = .5f,
            manual = VideoColorAdjustments(saturation = 15f, contrast = .07f))
        assertEquals(-35f, grade.resolved().saturation, .0001f)
        assertEquals(.155f, grade.resolved().contrast, .0001f)
        assertEquals(15f, grade.manual.saturation, 0f)
        val full = grade.copy(intensity = 1f)
        assertEquals(-85f, full.resolved().saturation, .0001f)
    }

    @Test fun independentRgbPresetAndManualClampSafely() {
        val grade = VideoColorGrade(
            preset = VideoFilterPreset.WARM,
            manual = VideoColorAdjustments(red = 2f, blue = 0f))
        assertEquals(2f, grade.resolved().red, 0f)
        assertEquals(0f, grade.resolved().blue, 0f)
        assertEquals(1f, grade.resolved().green, 0f)
    }

    @Test fun invalidLevelsAreRejectedBeforeReachingShaders() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.01f, 1.01f).forEach { amount ->
            assertThrows(IllegalArgumentException::class.java) {
                VideoColorGrade(intensity = amount)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoColorAdjustments(saturation = 100.1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoColorAdjustments(red = Float.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoColorAdjustments(contrast = -1.1f)
        }
    }

    @Test fun changingOneClipCannotChangeNeighborsOrMusic() {
        val audio = VideoAudioTrackDraft("music", "content://audio/a", 0L, 5000L)
        val original = VideoProjectDraft(listOf(clip(), clip().copy(id = "second")), listOf(audio))
        val graded = original.setColor("first",
            VideoColorGrade(preset = VideoFilterPreset.CINEMATIC, intensity = .75f))
        assertEquals(VideoColorGrade(), original.clips[0].color)
        assertEquals(VideoColorGrade(), graded.clips[1].color)
        assertEquals(original.audioTracks, graded.audioTracks)
        assertEquals(original.durationMs, graded.durationMs)
        assertFalse(graded.clips[0].color.isNeutral)
    }

    @Test fun splitDuplicateAndReorderPreservePerClipColor() {
        val project = VideoProjectDraft.single("first", "content://videos/1", 8000L)
            .setColor("first", VideoColorGrade(
                preset = VideoFilterPreset.VINTAGE,
                manual = VideoColorAdjustments(lightness = -10f)))
            .splitAt("first", 3000L, "right")
            .duplicate("right", "copy")
            .move("copy", 0)
        assertEquals(3, project.clips.size)
        assertTrue(project.clips.all { it.color.preset == VideoFilterPreset.VINTAGE })
        assertTrue(project.clips.all { it.color.manual.lightness == -10f })
        assertEquals(13000L, project.durationMs)
    }

    @Test fun invalidSelectedClipIsNotSilentlyIgnored() {
        val draft = VideoProjectDraft.single("first", "content://videos/1", 8000L)
        assertThrows(IllegalArgumentException::class.java) {
            draft.setColor("missing", VideoColorGrade(preset = VideoFilterPreset.MONO))
        }
    }
}

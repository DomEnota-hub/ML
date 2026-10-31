package com.domenota.medialoader.data.editor

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.Crop
import androidx.media3.effect.ScaleAndRotateTransformation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoColorEffectsStage5Test {
    private val editor by lazy { VideoEditor(RuntimeEnvironment.getApplication()) }

    @Test fun originalColorDoesNotAllocateShaders() {
        assertTrue(editor.colorEffects(VideoColorGrade()).isEmpty())
        assertTrue(editor.colorEffects(VideoColorGrade(
            preset = VideoFilterPreset.CINEMATIC, intensity = 0f)).isEmpty())
    }

    @Test fun eachColorChannelProducesRealMedia3Effects() {
        val grade = VideoColorGrade(
            preset = VideoFilterPreset.WARM,
            intensity = .75f,
            manual = VideoColorAdjustments(brightness = .1f, contrast = .15f,
                hue = 20f, lightness = 12f, saturation = 24f))
        val effects = editor.colorEffects(grade)
        assertEquals(4, effects.size)
        assertTrue(effects[0] is Brightness)
        assertTrue(effects[1] is Contrast)
        assertTrue(effects[2] is RgbAdjustment)
        assertTrue(effects[3] is HslAdjustment)
        assertEquals(20f, (effects[3] as HslAdjustment).hueAdjustmentDegrees, .0001f)
    }

    @Test fun isolatedGradingDoesNotModifyOtherClipsAudioOrGeometry() {
        val music = VideoAudioTrackDraft("song", "content://song", 0L, 6000L)
        val project = VideoProjectDraft(listOf(
            VideoClipDraft("a", "content://a", 9000L, speed = 2f,
                rotationDegrees = 90, crop = CropFractions(left = .1f),
                color = VideoColorGrade(preset = VideoFilterPreset.MONO)),
            VideoClipDraft("b", "content://b", 5000L)), listOf(music))
        val composition = editor.compositionForProject(project,
            VideoEditSpec(project = project))
        val clips = composition.sequences.single().editedMediaItems
        assertEquals(2, clips.size)
        val first = clips[0].effects.videoEffects
        val second = clips[1].effects.videoEffects
        assertTrue(first.any { it is Crop })
        assertTrue(first.any { it is ScaleAndRotateTransformation })
        assertTrue(first.any { it is Contrast })
        assertTrue(first.any { it is HslAdjustment })
        assertTrue(second.isEmpty())
        assertEquals(2f, clips[0].speedProvider.getSpeed(0L), .0001f)
        assertFalse(clips[0].removeAudio)
        assertEquals(1, project.audioTracks.size)
    }

    @Test fun previewAndExportUseSameEffectSequenceForAClip() {
        val grade = VideoColorGrade(
            preset = VideoFilterPreset.VINTAGE, intensity = .6f,
            manual = VideoColorAdjustments(saturation = 10f, blue = 1.07f))
        val project = VideoProjectDraft.single("a", "content://source", 10000L)
            .setColor("a", grade)
        val exportEffects = editor.compositionForProject(project,
            VideoEditSpec(project = project))
            .sequences.single().editedMediaItems.single().effects.videoEffects
        val previewEffects = editor.previewEffects(
            VideoEditSpec(color = grade))
        assertEquals(previewEffects.map { it.javaClass }, exportEffects.map { it.javaClass })
    }
}

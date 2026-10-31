package com.domenota.medialoader.data.editor

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Full staged integration contract: composition of mixed sources with cut
 * effects, audio placement, color, text, stickers and common export canvas.
 * These are structural tests; physical encoding still requires device QA.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoFinalStage9IntegrationTest {
    private val editor by lazy { VideoEditor(RuntimeEnvironment.getApplication()) }

    @Test fun multiStageProjectRetainsOneTimelineAndEveryEffect() {
        val videoA = VideoClipDraft("a", "content://video/a", 8000L,
            speed = 2f)
        val photoB = VideoClipDraft("b", "content://photo/b", 2500L,
            mediaKind = VideoClipMediaKind.IMAGE)
        val videoC = VideoClipDraft("c", "content://video/c", 6000L,
            speed = .5f, rotationDegrees = 90)
        val audio = VideoAudioTrackDraft("song", "content://audio/song", 1000L,
            9000L, volume = .65f)
        val project = VideoProjectDraft(listOf(videoA, photoB, videoC),
            listOf(audio))
            .setTransitionAfter("a",
                VideoTransitionDraft(VideoTransitionType.DIP_BLACK, 700L))
            .setTransitionAfter("b",
                VideoTransitionDraft(VideoTransitionType.FLASH, 700L))
            .setSpecialFx("c",
                VideoSpecialFxDraft(VideoSpecialFxType.VIGNETTE, .65f))
            .setColor("a",
                VideoColorGrade(preset = VideoFilterPreset.WARM))
        val spec = VideoEditSpec(
            project = project,
            textLayers = listOf(TextLayer(id = "title", text = "Тест ML",
                startMs = 3300L, endMs = 7400L)),
            graphicLayers = listOf(VideoGraphicLayer(id = "star", startMs = 2000L,
                endMs = 10000L)),
            exportSettings = VideoExportSettings(resolution =
                VideoExportResolution.HD_720, quality = VideoExportQuality.HIGH))
        val size = spec.exportSettings.dimensions(1920, 1080)
        val composition = editor.compositionForProject(project, spec, size)
        val items = composition.sequences.single().editedMediaItems

        assertEquals(3, items.size)
        assertEquals(18500L, project.durationMs)
        assertEquals(1, project.visibleAudioTracks().size)
        assertEquals(2, VideoTransitionTiming.enabledCuts(project))
        assertEquals(2f, items[0].speedProvider.getSpeed(0L), 0f)
        assertEquals(.5f, items[2].speedProvider.getSpeed(0L), 0f)
        assertEquals(2500L, items[1].mediaItem.localConfiguration!!.imageDurationMs)
        assertTrue(items.all { it.effects.videoEffects.last() is Presentation })
        assertTrue(items.all {
            it.effects.videoEffects.any { effect -> effect is OverlayEffect }
        })
        assertFalse(items.any { it.removeAudio })
        val spans = project.clips.indices.map { i ->
            VideoOverlayTimeline.forClip(project, i, spec.textLayers, spec.graphicLayers)
        }
        assertEquals(2, spans.count { it.texts.isNotEmpty() })
        assertEquals(3, spans.count { it.graphics.isNotEmpty() })
    }

    @Test fun mutedProjectStillRetainsVideoAndCanSelectLowFps() {
        val p = VideoProjectDraft(listOf(VideoClipDraft("v", "content://video/v", 4000L)))
        val spec = VideoEditSpec(project = p, removeAudio = true,
            exportSettings = VideoExportSettings(
                frameRate = VideoExportFrameRate.FPS_24,
                resolution = VideoExportResolution.SD_480))
        val media = editor.compositionForProject(p, spec,
            spec.exportSettings.dimensions(1920, 1080))
            .sequences.single().editedMediaItems.single()
        assertTrue(media.removeAudio)
        assertTrue(media.effects.videoEffects.last() is Presentation)
        assertEquals(24, spec.exportSettings.frameRate.maxFps)
    }
}

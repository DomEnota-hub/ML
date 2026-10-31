package com.domenota.medialoader.data.editor

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoCompositionStage7Test {
    private val editor = VideoEditor(RuntimeEnvironment.getApplication())

    @Test fun transitionsAndFxAreComposedIntoActualMedia3VideoEffects() {
        val p = VideoProjectDraft(listOf(
            VideoClipDraft("first", "content://v/1", 8000L, speed = 2f),
            VideoClipDraft("second", "content://v/2", 8000L, speed = .5f),
        )).setTransitionAfter("first",
            VideoTransitionDraft(VideoTransitionType.FLASH, 600L))
            .setSpecialFx("second",
                VideoSpecialFxDraft(VideoSpecialFxType.SCANLINES, .55f))
        val items = editor.compositionForProject(p, VideoEditSpec(project = p))
            .sequences.single().editedMediaItems
        assertEquals(2, items.size)
        assertEquals(1, items[0].effects.videoEffects.filterIsInstance<OverlayEffect>().size)
        assertEquals(1, items[1].effects.videoEffects.filterIsInstance<OverlayEffect>().size)
        assertEquals(2f, items[0].speedProvider.getSpeed(0L), 0f)
        assertEquals(.5f, items[1].speedProvider.getSpeed(0L), 0f)
        assertFalse(items[0].removeAudio)
        assertEquals(20000L, p.durationMs)
    }

    @Test fun unaffectedClipsHaveNoTransitionShaders() {
        val p = VideoProjectDraft(listOf(
            VideoClipDraft("a", "content://a", 3000L),
            VideoClipDraft("b", "content://b", 3000L),
            VideoClipDraft("c", "content://c", 3000L),
        )).setTransitionAfter("a",
            VideoTransitionDraft(VideoTransitionType.DIP_WHITE))
        val items = editor.compositionForProject(p, VideoEditSpec(project = p))
            .sequences.single().editedMediaItems
        assertEquals(1, items[0].effects.videoEffects.filterIsInstance<OverlayEffect>().size)
        assertEquals(1, items[1].effects.videoEffects.filterIsInstance<OverlayEffect>().size)
        assertTrue(items[2].effects.videoEffects.isEmpty())
    }

    @Test fun previewAndExportUseSameEffectClassesForActiveClip() {
        val p = VideoProjectDraft(listOf(
            VideoClipDraft("a", "content://a", 4000L),
            VideoClipDraft("b", "content://b", 4000L),
        )).setTransitionAfter("a",
            VideoTransitionDraft(VideoTransitionType.WIPE_BLACK, 1100L))
            .setSpecialFx("a",
                VideoSpecialFxDraft(VideoSpecialFxType.VIGNETTE, 1f))
        val spec = VideoEditSpec(specialFx = p.clips[0].specialFx,
            transitionPhases = VideoTransitionTiming.forClip(p, 0))
        val preview = editor.previewEffects(spec)
        val exported = editor.compositionForProject(p, VideoEditSpec(project = p))
            .sequences.single().editedMediaItems[0].effects.videoEffects
        assertEquals(preview.map { it.javaClass }, exported.map { it.javaClass })
        assertEquals(1, exported.filterIsInstance<OverlayEffect>().size)
    }
}

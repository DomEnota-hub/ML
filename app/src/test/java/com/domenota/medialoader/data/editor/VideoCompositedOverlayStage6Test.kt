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
class VideoCompositedOverlayStage6Test {
    private val editor = VideoEditor(RuntimeEnvironment.getApplication())

    @Test fun textAndStickersAreRealMedia3OverlayEffectsOnBothClips() {
        val project = VideoProjectDraft(listOf(
            VideoClipDraft("a", "content://video/a", 6000L, speed = 2f),
            VideoClipDraft("b", "content://video/b", 4000L),
        ))
        val spec = VideoEditSpec(project = project,
            textLayers = listOf(TextLayer(id = "title", text = "Title",
                startMs = 2500L, endMs = 5000L)),
            graphicLayers = listOf(VideoGraphicLayer(id = "shape",
                kind = VideoGraphicKind.CIRCLE, startMs = 1000L, endMs = 4500L)))
        val edited = editor.compositionForProject(project, spec)
            .sequences.single().editedMediaItems
        assertEquals(2, edited.size)
        assertEquals(1, edited[0].effects.videoEffects.filterIsInstance<OverlayEffect>().size)
        assertEquals(1, edited[1].effects.videoEffects.filterIsInstance<OverlayEffect>().size)
        assertEquals(2f, edited[0].speedProvider.getSpeed(0L), 0f)
        assertFalse(edited[0].removeAudio)
    }

    @Test fun graphicIsAbsentOnClipsOutsideItsTimeRange() {
        val p = VideoProjectDraft(listOf(
            VideoClipDraft("first", "content://a", 2000L),
            VideoClipDraft("second", "content://b", 2000L)))
        val spec = VideoEditSpec(project = p,
            graphicLayers = listOf(VideoGraphicLayer(startMs = 2500L, endMs = 3500L)))
        val edited = editor.compositionForProject(p, spec)
            .sequences.single().editedMediaItems
        assertTrue(edited[0].effects.videoEffects.none { it is OverlayEffect })
        assertEquals(1, edited[1].effects.videoEffects.filterIsInstance<OverlayEffect>().size)
        assertEquals(1, editor.previewEffects(VideoEditSpec(graphicLayers =
            spec.graphicLayers)).filterIsInstance<OverlayEffect>().size)
    }

    @Test fun boundsProtectExportFromTooManyLayers() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoEditSpec(textLayers = List(9) { TextLayer(text = "x") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoEditSpec(graphicLayers = List(13) {
                VideoGraphicLayer(kind = VideoGraphicKind.STAR)
            })
        }
    }
}

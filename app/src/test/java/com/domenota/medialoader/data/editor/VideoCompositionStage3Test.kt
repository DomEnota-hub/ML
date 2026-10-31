package com.domenota.medialoader.data.editor

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
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
class VideoCompositionStage3Test {
    @Test fun exportedCompositionChangesPlaybackSpeedForVideoAndAudio() {
        val draft = VideoProjectDraft(listOf(
            VideoClipDraft("a", "content://media/a", 12000, speed = 2f),
            VideoClipDraft("b", "content://media/b", 7000, speed = .5f),
        ))
        val media3 = VideoEditor(RuntimeEnvironment.getApplication())
            .compositionForProject(draft, VideoEditSpec(project = draft))
        val items = media3.sequences.single().editedMediaItems
        assertEquals(2, items.size)
        assertEquals(2f, items[0].speedProvider.getSpeed(0L))
        assertEquals(.5f, items[1].speedProvider.getSpeed(0L))
        assertTrue(items.all { !it.removeAudio })
        assertEquals(20000L, draft.durationMs)
    }

    @Test fun mirrorAndRotationAreAppliedOnlyToSelectedClip() {
        val draft = VideoProjectDraft(listOf(
            VideoClipDraft("a", "content://media/a", 12000,
                rotationDegrees = 90, mirrorHorizontal = true,
                crop = CropFractions(left = .125f)),
            VideoClipDraft("b", "content://media/b", 7000),
        ))
        val items = VideoEditor(RuntimeEnvironment.getApplication())
            .compositionForProject(draft, VideoEditSpec(project = draft))
            .sequences.single().editedMediaItems
        val transforms = items[0].effects.videoEffects
            .filterIsInstance<ScaleAndRotateTransformation>()
        assertEquals(1, transforms.size)
        assertEquals(-1f, transforms.single().scaleX)
        assertEquals(1f, transforms.single().scaleY)
        assertTrue(items[1].effects.videoEffects.isEmpty())
    }

    @Test fun eachClipKeepsItsOwnCropInExport() {
        val draft = VideoProjectDraft(listOf(
            VideoClipDraft("a", "content://media/a", 5000,
                crop = CropFractions(top = .1f)),
            VideoClipDraft("b", "content://media/b", 5000),
        ))
        val items = VideoEditor(RuntimeEnvironment.getApplication())
            .compositionForProject(draft, VideoEditSpec(project = draft))
            .sequences.single().editedMediaItems
        assertEquals(1, items[0].effects.videoEffects.size)
        assertTrue(items[1].effects.videoEffects.isEmpty())
    }
}

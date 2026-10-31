package com.domenota.medialoader.data.editor

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoMixedMediaCompositionStage8Test {
    private val editor by lazy { VideoEditor(RuntimeEnvironment.getApplication()) }

    @Test fun stillPhotoAndVideoAreComposedInSameSequenceWithOutputCanvas() {
        val project = VideoProjectDraft(listOf(
            VideoClipDraft("movie", "content://videos/1", 5000L,
                rotationDegrees = 90, speed = 2f),
            VideoClipDraft("photo", "content://photos/1", 3000L,
                mediaKind = VideoClipMediaKind.IMAGE),
        ))
        val settings = VideoExportSettings(
            resolution = VideoExportResolution.HD_720,
            frameRate = VideoExportFrameRate.FPS_30)
        val canvas = settings.dimensions(1080, 1920)
        val media3 = editor.compositionForProject(project,
            VideoEditSpec(project = project, exportSettings = settings), canvas)
        val items = media3.sequences.single().editedMediaItems
        assertEquals(2, items.size)
        assertEquals(3000L, items[1].mediaItem.localConfiguration!!.imageDurationMs)
        assertEquals(2f, items[0].speedProvider.getSpeed(0L), .001f)
        assertTrue(items.all { edited ->
            edited.effects.videoEffects.any { it is Presentation }
        })
        assertEquals(6500L, project.durationMs)
        assertFalse(items[0].removeAudio)
    }

    @Test fun everyClipGetsCanvasIndependentOfItsSourceAspect() {
        val project = VideoProjectDraft(listOf(
            VideoClipDraft("portrait", "content://videos/p", 2000L),
            VideoClipDraft("landscape", "content://videos/l", 3000L),
            VideoClipDraft("image", "content://photos/1", 4000L,
                mediaKind = VideoClipMediaKind.IMAGE),
        ))
        val canvas = VideoExportDimensions(720, 1280)
        val edited = editor.compositionForProject(project,
            VideoEditSpec(project = project), canvas)
            .sequences.single().editedMediaItems
        assertEquals(3, edited.size)
        assertTrue(edited.all { it.effects.videoEffects.last() is Presentation })
        assertEquals(9000L, project.durationMs)
    }

    @Test fun imageAndVideoPreviewQueueUseDifferentTrimRules() {
        val project = VideoProjectDraft(listOf(
            VideoClipDraft("photo", "content://images/p", 5000L,
                mediaKind = VideoClipMediaKind.IMAGE),
            VideoClipDraft("movie", "content://videos/m", 8000L, inMs = 1000L,
                outMs = 5000L),
        ))
        val items = listOf(
            androidx.media3.common.MediaItem.Builder()
                .setUri("content://images/p").setImageDurationMs(5000L)
                .setMediaId("image:photo:5000").build(),
            androidx.media3.common.MediaItem.Builder()
                .setUri("content://videos/m")
                .setClippingConfiguration(
                    androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(1000L).setEndPositionMs(5000L).build())
                .build(),
        )
        assertTrue(VideoPreviewQueueSync.matches(items, project))
        assertFalse(VideoPreviewQueueSync.matches(items,
            project.setPhotoDuration("photo", 6000L)))
    }
}

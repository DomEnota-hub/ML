package com.domenota.medialoader.data.editor

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoCompositionStage2Test {
    private val editor: VideoEditor
        get() = VideoEditor(RuntimeEnvironment.getApplication<android.app.Application>())

    @Test fun compositionOrderAndClipBoundariesMatchTheTimeline() {
        val draft = VideoProjectDraft(listOf(
            VideoClipDraft("a", "content://media/video-a", 10000, 2000, 7000),
            VideoClipDraft("b", "content://media/video-b", 8000, 1000, 4000),
        )).move("b", 0)
        val media3 = editor.compositionForProject(draft, VideoEditSpec(project = draft))
        assertEquals(1, media3.sequences.size)
        val items = media3.sequences.first().editedMediaItems
        assertEquals(2, items.size)
        assertEquals("content://media/video-b", items[0].mediaItem.localConfiguration?.uri.toString())
        assertEquals(1000L, items[0].mediaItem.clippingConfiguration.startPositionMs)
        assertEquals(4000L, items[0].mediaItem.clippingConfiguration.endPositionMs)
        assertEquals("content://media/video-a", items[1].mediaItem.localConfiguration?.uri.toString())
        assertEquals(2000L, items[1].mediaItem.clippingConfiguration.startPositionMs)
        assertEquals(7000L, items[1].mediaItem.clippingConfiguration.endPositionMs)
    }

    @Test fun sourceFilesRemainReferencedWithoutReencodingOrOverwritingOnDraftEdit() {
        val source = VideoClipDraft("a", "content://media/original", 16000)
        val draft = VideoProjectDraft(listOf(source)).splitAt("a", 6200, "b")
        val composition = editor.compositionForProject(draft, VideoEditSpec(project = draft))
        val items = composition.sequences.first().editedMediaItems
        assertEquals(2, items.size)
        assertEquals("content://media/original", items[0].mediaItem.localConfiguration?.uri.toString())
        assertEquals("content://media/original", items[1].mediaItem.localConfiguration?.uri.toString())
        assertEquals(0L, items[0].mediaItem.clippingConfiguration.startPositionMs)
        assertEquals(6200L, items[0].mediaItem.clippingConfiguration.endPositionMs)
        assertEquals(6200L, items[1].mediaItem.clippingConfiguration.startPositionMs)
        assertEquals(16000L, items[1].mediaItem.clippingConfiguration.endPositionMs)
    }
}

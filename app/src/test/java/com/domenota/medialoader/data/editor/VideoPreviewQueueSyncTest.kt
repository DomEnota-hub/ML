package com.domenota.medialoader.data.editor

import android.net.Uri
import androidx.media3.common.MediaItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoPreviewQueueSyncTest {
    private val first = VideoClipDraft("one", "content://videos/one", 10000, 1000, 8000)
    private val second = VideoClipDraft("two", "content://videos/two", 7000, 0, 6000)
    private val draft = VideoProjectDraft(listOf(first, second))

    private fun playlist(project: VideoProjectDraft): List<MediaItem> =
        project.clips.map { clip ->
            MediaItem.Builder().setUri(Uri.parse(clip.sourceUri))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.inMs)
                        .setEndPositionMs(clip.outMs)
                        .build(),
                )
                .build()
        }

    @Test fun matchingPlaylistPermitsTimelinePositionUpdates() {
        assertTrue(VideoPreviewQueueSync.matches(playlist(draft), draft))
    }

    @Test fun stalePlaylistIsRejectedWhenReorderedOrSplit() {
        val oldItems = playlist(draft)
        assertFalse(VideoPreviewQueueSync.matches(oldItems, draft.move("two", 0)))
        assertFalse(VideoPreviewQueueSync.matches(
            oldItems, draft.splitAt("one", 4000, "right")))
    }

    @Test fun stalePlaylistIsRejectedWhenTrimOrSourceChangesWithoutCountChange() {
        val oldItems = playlist(draft)
        assertFalse(VideoPreviewQueueSync.matches(
            oldItems, draft.trimClip("one", 2000, 7000)))
        assertFalse(VideoPreviewQueueSync.matches(
            oldItems, draft.replace("two", "content://videos/replacement", 9000)))
    }

    @Test fun acceptsNewPlaylistAfterRebuild() {
        val revised = draft.trimClip("one", 2000, 7000).move("two", 0)
        assertTrue(VideoPreviewQueueSync.matches(playlist(revised), revised))
    }
}

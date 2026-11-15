package com.domenota.medialoader.data.editor

import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test

class VideoEditorPreviewFailurePolicyTest {
    @Test fun failedPreviewNeverAutoPreparesOnRecompositionOrResume() {
        assertTrue(VideoEditorPreviewFailurePolicy.shouldAutoPrepare(Player.STATE_IDLE, 1, false))
        assertFalse(VideoEditorPreviewFailurePolicy.shouldAutoPrepare(Player.STATE_IDLE, 1, true))
        assertFalse(VideoEditorPreviewFailurePolicy.shouldAutoPrepare(Player.STATE_IDLE, 0, false))
        assertFalse(VideoEditorPreviewFailurePolicy.shouldAutoPrepare(Player.STATE_READY, 1, false))
    }

    private val project = VideoProjectDraft(listOf(
        VideoClipDraft("one", "content://test/one", 5000L, inMs = 1000L, outMs = 4000L),
        VideoClipDraft("two", "content://test/two", 8000L, inMs = 2000L, outMs = 6000L, speed = 2f),
    ))

    @Test fun retryAtEndRestartsProjectInsteadOfSeekingPastLastFrame() {
        val target = VideoEditorPreviewFailurePolicy.retryTarget(project, project.durationMs)
        assertEquals(VideoEditorPreviewFailurePolicy.RetryTarget(0L, 0, 0L), target)
    }

    @Test fun retryInTrimmedFastClipUsesSourceOffsetWithoutChangingDraft() {
        val target = VideoEditorPreviewFailurePolicy.retryTarget(project, 3500L)
        assertEquals(VideoEditorPreviewFailurePolicy.RetryTarget(3500L, 1, 1000L), target)
        assertEquals(2000L, project.clips[1].inMs)
        assertEquals(2f, project.clips[1].speed)
    }
}

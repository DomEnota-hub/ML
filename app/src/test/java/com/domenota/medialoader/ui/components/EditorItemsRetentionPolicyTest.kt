package com.domenota.medialoader.ui.components

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.ui.model.DownloadUiItem
import com.domenota.medialoader.ui.model.DownloadUiState
import com.domenota.medialoader.ui.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorItemsRetentionPolicyTest {
    private fun source(id: Int): DownloadEntity = DownloadEntity(
        "video-$id", "device", "video-$id.mp4", MediaType.VIDEO,
        DownloadState.COMPLETED, "content://media/external/video/media/$id",
        1024L, 0L,
    )

    private fun card(row: DownloadEntity): DownloadUiItem = DownloadUiItem(
        id = row.id,
        title = row.originalName,
        subtitle = "Видео",
        sizeLabel = null,
        state = DownloadUiState.COMPLETED,
        savedUri = row.savedUri,
        kind = MediaKind.VIDEO,
    )

    @Test fun preservesAll212VideosWhenOnlyMetadataOrListIdentityChanges() {
        val old = (1..212).map(::source)
        val shown = old.map(::card)
        val updated = old.map { it.copy(sizeBytes = 4096L) }
        val retained = retainEditorFilesForCurrentSource(shown, updated)
        assertEquals(212, retained.size)
        assertEquals(shown, retained)
    }

    @Test fun instantlyHidesRemovedHiddenOrChangedUriRows() {
        val old = (1..4).map(::source)
        val current = listOf(
            old[0].copy(sizeBytes = 2048L),
            old[1].copy(hidden = true),
            old[2].copy(savedUri = "content://media/external/video/media/999"),
        )
        val retained = retainEditorFilesForCurrentSource(old.map(::card), current)
        assertEquals(listOf("video-1"), retained.map { it.id })
    }

    @Test fun noCurrentAuthorizedDataMeansNoOldFiles() {
        val video = source(8)
        assertTrue(retainEditorFilesForCurrentSource(listOf(card(video)), emptyList()).isEmpty())
        assertTrue(retainEditorFilesForCurrentSource(
            listOf(card(video)), listOf(video.copy(state = DownloadState.CANCELLED)),
        ).isEmpty())
    }
}

package com.domenota.medialoader.data.playback

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.storage.SavedFileStatus
import org.junit.Assert.*
import org.junit.Test

class GridMediaQueueTest {
    private fun row(id: String, type: MediaType = MediaType.PHOTO) = DownloadEntity(
        id, "test", "$id.jpg", type, DownloadState.COMPLETED, "file:///$id", null, 0, groupId = id)
    @Test fun snapshotOrderCrossesGroupsAndIncludesVideoButNeverAudio() {
        val rows = listOf(row("a"), row("b", MediaType.VIDEO), row("c"), row("song", MediaType.AUDIO))
        assertEquals(listOf("c", "b", "a"), GridMediaQueue.resolve(listOf("c", "song", "b", "a", "b"), rows, false).map { it.id })
    }
    @Test fun rejectsOppositePrivacyHiddenUnavailableAndUnfinishedRows() {
        val rows = listOf(row("a"), row("private").copy(isPrivate = true), row("hidden").copy(hidden = true),
            row("pending").copy(state = DownloadState.QUEUED), row("missing"), row("denied"), row("blank").copy(savedUri = null))
        val ids = rows.map { it.id }
        val statuses = mapOf("missing" to SavedFileStatus.MISSING, "denied" to SavedFileStatus.INACCESSIBLE)
        assertEquals(listOf("a"), GridMediaQueue.resolve(ids, rows, false, statuses).map { it.id })
        assertEquals(listOf("private"), GridMediaQueue.resolve(ids, rows, true, statuses).map { it.id })
    }
}

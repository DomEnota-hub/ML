package com.domenota.medialoader.data.playback

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.storage.SavedFileStatus

/** Fail-closed vault-only gallery queue; NEVER use public media aliases here. */
internal object PrivateViewerQueue {
    fun resolve(
        ids: List<String>, requestedId: String?, unlocked: Boolean,
        rows: List<DownloadEntity>, statuses: Map<String, SavedFileStatus>,
    ): List<DownloadEntity> {
        if (!unlocked) return emptyList()
        val visibleIds = ids.ifEmpty { listOfNotNull(requestedId) }
        val rowsFromGrid = GridMediaQueue.resolve(visibleIds, rows, true, statuses)
        // Single-file opens and stale grid snapshots still have the user's
        // requested private file first, but no public fallback is possible.
        val requested = GridMediaQueue.resolve(listOfNotNull(requestedId), rows,
            true, statuses)
        return (requested + rowsFromGrid).distinctBy { it.id }
    }
}

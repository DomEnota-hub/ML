package com.domenota.medialoader.ui.model

import com.domenota.medialoader.core.database.CatalogAssignmentEntity

/** Logical history filter; active transfers are handled separately by DownloadsScreen. */
object DownloadCatalogSelection {
    const val ALL = "all"
    const val FAVORITES = "favorites"
    const val UNFILED = "unfiled"

    fun folder(id: String) = "folder:$id"

    fun includes(key: String, assignment: CatalogAssignmentEntity?,
                 hiddenFolderIds: Set<String> = emptySet()): Boolean {
        val folderId = assignment?.folderId
        // A hidden folder is still an ordinary folder; only explicit navigation
        // to this exact folder bypasses the general-media presentation filter.
        if (folderId != null && folderId in hiddenFolderIds && key != folder(folderId)) return false
        return when (key) {
            ALL -> true
            FAVORITES -> assignment?.isFavorite == true
            UNFILED -> assignment?.folderId == null
            else -> key.startsWith("folder:") && key.removePrefix("folder:") == assignment?.folderId
        }
    }

    fun includesHistoryItem(
        key: String,
        state: DownloadUiState,
        assignment: CatalogAssignmentEntity?,
        hiddenFolderIds: Set<String> = emptySet(),
    ): Boolean = if (key == ALL) includes(key, assignment, hiddenFolderIds)
        else state == DownloadUiState.COMPLETED && includes(key, assignment, hiddenFolderIds)
}

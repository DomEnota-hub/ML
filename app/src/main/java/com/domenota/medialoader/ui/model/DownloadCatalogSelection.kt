package com.domenota.medialoader.ui.model

import com.domenota.medialoader.core.database.CatalogAssignmentEntity

/** Logical history filter; active transfers are handled separately by DownloadsScreen. */
object DownloadCatalogSelection {
    const val ALL = "all"
    const val FAVORITES = "favorites"
    const val UNFILED = "unfiled"

    fun folder(id: String) = "folder:$id"

    fun includes(key: String, assignment: CatalogAssignmentEntity?): Boolean = when (key) {
        ALL -> true
        FAVORITES -> assignment?.isFavorite == true
        UNFILED -> assignment?.folderId == null
        else -> key.startsWith("folder:") && key.removePrefix("folder:") == assignment?.folderId
    }

    fun includesHistoryItem(
        key: String,
        state: DownloadUiState,
        assignment: CatalogAssignmentEntity?,
    ): Boolean = key == ALL || (state == DownloadUiState.COMPLETED && includes(key, assignment))
}

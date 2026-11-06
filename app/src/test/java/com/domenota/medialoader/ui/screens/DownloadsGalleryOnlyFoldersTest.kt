package com.domenota.medialoader.ui.screens

import com.domenota.medialoader.core.database.CatalogAssignmentEntity
import com.domenota.medialoader.core.database.CatalogFolderEntity
import com.domenota.medialoader.ui.model.DownloadUiItem
import com.domenota.medialoader.ui.model.DownloadUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadsGalleryOnlyFoldersTest {
    private val folders = listOf(
        CatalogFolderEntity("from-gallery", "Отбросы", "отбросы", 1L),
        CatalogFolderEntity("downloads", "Загрузки", "загрузки", 2L),
        CatalogFolderEntity("empty", "Пустая папка", "пустая папка", 3L),
    )
    private val rows = listOf(
        DownloadUiItem("app-download", "Video.mp4", "Видео", null,
            state = DownloadUiState.COMPLETED),
    )
    @Test fun galleryOnlyFoldersHiddenInHistoryNotDeletedOrModified() {
        val entries = mapOf("app-download" to
            CatalogAssignmentEntity("app-download", "downloads", false))
        assertEquals(listOf("downloads", "empty"),
            visibleDownloadFolders(folders, rows, entries, setOf("from-gallery"),
                assigning = false).map { it.id })
        assertEquals(folders.map { it.id },
            visibleDownloadFolders(folders, rows, entries, setOf("from-gallery"),
                assigning = true).map { it.id })
    }
    @Test fun folderWithRealDownloadRemainsVisibleEvenIfItAlsoHasGalleryMedia() {
        val entries = mapOf("app-download" to
            CatalogAssignmentEntity("app-download", "from-gallery", false))
        assertEquals(folders.map { it.id },
            visibleDownloadFolders(folders, rows, entries, setOf("from-gallery"),
                assigning = false).map { it.id })
    }
    @Test fun unfinishedDownloadDoesNotPretendToBeAvailableHistory() {
        val entries = mapOf("app-download" to
            CatalogAssignmentEntity("app-download", "from-gallery", false))
        assertEquals(listOf("downloads", "empty"),
            visibleDownloadFolders(folders,
                rows.map { it.copy(state = DownloadUiState.DOWNLOADING) },
                entries, setOf("from-gallery"), assigning = false).map { it.id })
    }
}

package com.domenota.medialoader.ui.model

import com.domenota.medialoader.core.database.CatalogAssignmentEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCatalogSelectionTest {
    private val favoriteInFolder = CatalogAssignmentEntity("a", "folder-1", true)
    private val ordinaryInFolder = CatalogAssignmentEntity("b", "folder-2", false)

    @Test fun favoriteIsIndependentOfFolder() {
        assertTrue(DownloadCatalogSelection.includes(DownloadCatalogSelection.FAVORITES, favoriteInFolder))
        assertTrue(DownloadCatalogSelection.includes(DownloadCatalogSelection.folder("folder-1"), favoriteInFolder))
        assertFalse(DownloadCatalogSelection.includes(DownloadCatalogSelection.folder("folder-2"), favoriteInFolder))
    }

    @Test fun hiddenFolderIsExcludedFromGeneralHistoryFavoritesAndSearchInput() {
        val hidden = setOf("folder-1")
        for (key in listOf(DownloadCatalogSelection.ALL, DownloadCatalogSelection.FAVORITES,
            DownloadCatalogSelection.UNFILED, DownloadCatalogSelection.folder("folder-2"))) {
            assertFalse(DownloadCatalogSelection.includesHistoryItem(key, DownloadUiState.COMPLETED,
                favoriteInFolder, hidden))
        }
        assertTrue(DownloadCatalogSelection.includesHistoryItem(DownloadCatalogSelection.folder("folder-1"),
            DownloadUiState.COMPLETED, favoriteInFolder, hidden))
        assertTrue(DownloadCatalogSelection.includesHistoryItem(DownloadCatalogSelection.ALL,
            DownloadUiState.COMPLETED, ordinaryInFolder, hidden))
        assertTrue(DownloadCatalogSelection.includes(DownloadCatalogSelection.FAVORITES, favoriteInFolder))
    }

    @Test fun oldDownloadsWithoutAssignmentsAppearUnderAllAndUnfiled() {
        assertTrue(DownloadCatalogSelection.includes(DownloadCatalogSelection.ALL, null))
        assertTrue(DownloadCatalogSelection.includes(DownloadCatalogSelection.UNFILED, null))
        assertFalse(DownloadCatalogSelection.includes(DownloadCatalogSelection.FAVORITES, null))
        assertFalse(DownloadCatalogSelection.includes(DownloadCatalogSelection.UNFILED, ordinaryInFolder))
        assertFalse(DownloadCatalogSelection.includesHistoryItem(
            DownloadCatalogSelection.UNFILED, DownloadUiState.FAILED, null))
        assertTrue(DownloadCatalogSelection.includesHistoryItem(
            DownloadCatalogSelection.ALL, DownloadUiState.FAILED, null))
    }
}

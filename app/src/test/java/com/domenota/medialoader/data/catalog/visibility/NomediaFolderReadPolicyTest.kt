package com.domenota.medialoader.data.catalog.visibility

import org.junit.Assert.*
import org.junit.Test

class NomediaFolderReadPolicyTest {
    @Test fun folderHideExcludesOrdinaryCatalogWithoutInventingLegacyReceipts() {
        val policy=FolderVisibilityReadPolicy(FolderVisibilitySnapshot(),
            nomediaHiddenFolders=setOf("album-a"))
        assertTrue(policy.isExcluded("public-1","album-a","content://media/external/images/media/8"))
        assertFalse(policy.isExcluded("public-2","album-b","content://media/external/images/media/9"))
        assertFalse(policy.canOpenHidden("public-1","album-a","content://media/external/images/media/8","album-a"))
        assertTrue(policy.snapshot.receipts.isEmpty())
        assertTrue(policy.snapshot.retiredLocators.isEmpty())
    }

    @Test fun interruptedMarkerStateIsConservativelyHiddenOnEveryRestart() {
        val r=NomediaFolderRecord("album","content://example/tree",true,NomediaMarkerState.PENDING_SHOW,
            ownedDocumentId="marker")
        assertTrue(NomediaFolderSnapshot(mapOf("album" to r)).hiddenFolderIds.contains("album"))
        assertFalse(r.markerVerified)
    }

    @Test fun foreignVisibleMarkerDoesNotManufactureHiddenMlStatus() {
        val r=NomediaFolderRecord("album","content://example/tree",false,NomediaMarkerState.FOREIGN_MARKER)
        assertTrue(NomediaFolderSnapshot(mapOf("album" to r)).hiddenFolderIds.isEmpty())
        assertTrue(r.markerVerified)
    }
}

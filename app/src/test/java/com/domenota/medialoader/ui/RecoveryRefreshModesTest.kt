package com.domenota.medialoader.ui

import com.domenota.medialoader.data.storage.HistoricalMediaKind
import org.junit.Assert.*
import org.junit.Test

class RecoveryRefreshModesTest {
    @Test fun visualHideNeverForcesUnrelatedMusicProviderRefresh() {
        assertEquals(setOf(AppMode.GALLERY),
            recoveryRefreshModes(setOf(HistoricalMediaKind.VISUAL)))
    }

    @Test fun audioShowNeverForcesUnrelatedGalleryProviderRefresh() {
        assertEquals(setOf(AppMode.MUSIC),
            recoveryRefreshModes(setOf(HistoricalMediaKind.AUDIO)))
    }

    @Test fun queuedExternalChangesAreAlsoRefreshedAfterRecovery() {
        assertEquals(setOf(AppMode.GALLERY,AppMode.MUSIC),
            recoveryRefreshModes(setOf(HistoricalMediaKind.VISUAL),setOf(AppMode.MUSIC)))
        assertEquals(setOf(AppMode.GALLERY,AppMode.MUSIC),
            recoveryRefreshModes(setOf(HistoricalMediaKind.AUDIO),setOf(AppMode.GALLERY)))
    }

    @Test fun downloaderIsNotAPhoneMediaProvider() {
        assertEquals(setOf(AppMode.GALLERY),
            recoveryRefreshModes(setOf(HistoricalMediaKind.VISUAL),setOf(AppMode.DOWNLOADER)))
    }
}

package com.domenota.medialoader.data.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreDateAuditPolicyTest {
    @Test fun migrationMtimeFixDoesNotChangeNewInsertionDate() {
        val original = 1_600_000_000_000L
        val addedToday = original + 100L * 86_400_000L
        val state = MediaStoreDateAuditPolicy.analyze(original, addedToday, original, 0L)
        assertTrue(state.addedNewerThanOriginal)
        assertTrue(state.modifiedRestoredButAddedNew)
        assertFalse(state.hasTakenDate)
    }

    @Test fun normalDownloadIsNotMisidentifiedAsMigration() {
        val original = 1_600_000_000_000L
        val state = MediaStoreDateAuditPolicy.analyze(original, original + 10_000L,
            original + 10_000L, 0L)
        assertFalse(state.addedNewerThanOriginal)
        assertFalse(state.modifiedRestoredButAddedNew)
    }

    @Test fun metadataDateTakenIsDistinctFromDownloadDate() {
        val original = 1_600_000_000_000L
        val state = MediaStoreDateAuditPolicy.analyze(original,
            original + 20L * 86_400_000L, original + 20L * 86_400_000L,
            original - 365L * 86_400_000L)
        assertTrue(state.addedNewerThanOriginal)
        assertFalse(state.modifiedRestoredButAddedNew)
        assertTrue(state.hasTakenDate)
    }
}

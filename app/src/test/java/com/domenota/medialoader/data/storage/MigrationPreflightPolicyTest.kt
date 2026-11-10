package com.domenota.medialoader.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationPreflightPolicyTest {
    private val old = 1790000000000L
    private fun input() = MigrationPreflightPolicy.Input(
        completed = true, hidden = false, privateItem = false, photoOrVideo = true,
        hasUri = true, mediaStoreUri = true, validHistoryDate = true,
        uniqueHistoryUri = true, ownerMatches = true, correctPath = true,
        nameMatches = true, supportedMime = true, positiveSize = true,
        sizeMatches = true, pendingOrTrashed = false, accessible = true,
        vkReferences = 0, externalCatalogReferences = 0,
        auxiliaryIndexesReadable = true,
        addedMillis = old + MigrationPreflightPolicy.ONE_DAY_MS * 3,
        historyMillis = old,
    )

    @Test fun oldOwnedDistinctMediaCanOnlyBeCandidateForFutureManualQA() {
        assertEquals(MigrationPreflightPolicy.CANDIDATE,
            MigrationPreflightPolicy.decision(input()))
    }

    @Test fun privateOrHiddenMustNeverBecomeCandidate() {
        assertEquals("BLOCKED", MigrationPreflightPolicy.decision(input().copy(privateItem = true)))
        assertEquals("BLOCKED", MigrationPreflightPolicy.decision(input().copy(hidden = true)))
    }

    @Test fun duplicateOrAuxiliaryReferencesAreBlocked() {
        for (x in listOf(input().copy(uniqueHistoryUri = false),
            input().copy(vkReferences = 2),
            input().copy(externalCatalogReferences = 1),
            input().copy(auxiliaryIndexesReadable = false))) {
            assertEquals("BLOCKED", MigrationPreflightPolicy.decision(x))
        }
    }

    @Test fun missingForeignOrUnsupportedMediaBlocksMigration() {
        for (x in listOf(input().copy(hasUri = false),
            input().copy(mediaStoreUri = false), input().copy(ownerMatches = false),
            input().copy(supportedMime = false), input().copy(correctPath = false),
            input().copy(nameMatches = false), input().copy(accessible = false),
            input().copy(sizeMatches = false), input().copy(validHistoryDate = false),
            input().copy(pendingOrTrashed = true))) {
            assertEquals("BLOCKED", MigrationPreflightPolicy.decision(x))
        }
    }

    @Test fun mediaAddedNearQueueTimestampIsNotForcedIntoMigration() {
        assertEquals(MigrationPreflightPolicy.RECENT,
            MigrationPreflightPolicy.decision(input().copy(addedMillis = old + 1000)))
        assertTrue(MigrationPreflightPolicy.blockers(input()).isEmpty())
    }
}

package com.domenota.medialoader.ui

import org.junit.Assert.*
import org.junit.Test

class LibraryValidationGateTest {
    @Test fun everyLibrarySurfaceStaysShieldedUntilCorrespondingIndexIsPublished() {
        val validated = PhoneLibraryState(loaded = true, revision = 1)
        val index = LibraryIndex(ready = true, galleryRevision = 1, musicRevision = 1)
        assertFalse(phoneValidationPending(index, validated, validated))
        val refreshing = validated.copy(loading = true, loaded = false, revision = 2)
        assertTrue(phoneValidationPending(index, refreshing, validated))
        assertTrue(phoneValidationPending(index, validated, refreshing))
        // scan() has returned but the canonical identity index is still the previous snapshot.
        val scanned = validated.copy(revision = 3)
        assertTrue(phoneValidationPending(index, scanned, validated))
        assertFalse(phoneValidationPending(index.copy(galleryRevision = 3), scanned, validated))
        // Publishing a retained-row index during the scan is insufficient too.
        assertTrue(phoneValidationPending(index.copy(galleryRevision = 2), refreshing, validated))
    }
    @Test fun backgroundRefreshKeepsValidatedContentUsableButPermissionChangeStillShieldsIt() {
        val retained = PhoneLibraryState(loaded = true, loading = true, revision = 2, requiresValidation = false)
        val index = LibraryIndex(ready = true, galleryRevision = 1, musicRevision = 1)
        assertFalse(phoneValidationPending(index, retained, retained))
        assertTrue(phoneValidationPending(index, retained.copy(requiresValidation = true), retained))
        val scanned = retained.copy(loading = false, revision = 3)
        assertFalse(phoneValidationPending(index, scanned, retained))
        assertTrue(phoneValidationPending(index, scanned.copy(requiresValidation = true), retained))
    }

}

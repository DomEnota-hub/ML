package com.domenota.medialoader.ui

import org.junit.Assert.*
import org.junit.Test

class LibraryValidationGateTest {
    @Test fun validatedFirstPageBecomesVisibleWhileRemainingScanContinues() {
        val partial = PhoneLibraryState(loaded = true, loading = true, revision = 7)
        val other = PhoneLibraryState()
        assertTrue(phoneValidationPending(LibraryIndex(galleryRevision = 6), partial, other))
        assertFalse(phoneValidationPending(LibraryIndex(ready = true, galleryRevision = 7), partial, other))
    }
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
    @Test fun unrelatedAudioValidationNeverMasksAnAlreadyValidatedGallery() {
        val ready = LibraryIndex(ready = true, galleryRevision = 7, musicRevision = 3)
        val photos = PhoneLibraryState(loaded = true, revision = 7, requiresValidation = true)
        val audio = PhoneLibraryState(loaded = false, loading = true, revision = 4, requiresValidation = true)
        assertTrue(phoneValidationPending(ready, photos, audio))
        assertFalse(visibleLibraryValidationPending(AppMode.GALLERY, ready, photos, audio))
        assertTrue(visibleLibraryValidationPending(AppMode.MUSIC, ready, photos, audio))
        assertTrue(visibleLibraryValidationPending(AppMode.GALLERY,
            ready, photos.copy(revision = 8), audio))
    }

    @Test fun galleryStillBlocksWhenItsOwnPermissionOrPrivacyIsUnvalidated() {
        val ready = LibraryIndex(ready = true, galleryRevision = 7, musicRevision = 7)
        val photos = PhoneLibraryState(loaded = false, loading = true, revision = 8, requiresValidation = true)
        assertTrue(visibleLibraryValidationPending(AppMode.GALLERY, ready, photos, PhoneLibraryState()))
        assertTrue(visibleLibraryValidationPending(AppMode.GALLERY,
            LibraryIndex(), photos, PhoneLibraryState()))
        assertFalse(visibleLibraryValidationPending(AppMode.DOWNLOADER,
            LibraryIndex(), photos, PhoneLibraryState()))
    }

    @Test fun mediaStoreNotificationsRefreshOnlyTheRelevantLibrary() {
        assertEquals(setOf(AppMode.GALLERY), phoneModesForMediaChange(
            "content://media/external_primary/images/media/123"))
        assertEquals(setOf(AppMode.GALLERY), phoneModesForMediaChange(
            "content://media/external/video/media"))
        assertEquals(setOf(AppMode.MUSIC), phoneModesForMediaChange(
            "content://media/external_primary/audio/media/44"))
        assertEquals(setOf(AppMode.GALLERY, AppMode.MUSIC),
            phoneModesForMediaChange("content://media/external/file/20"))
        assertEquals(setOf(AppMode.GALLERY, AppMode.MUSIC), phoneModesForMediaChange(null))
    }

    @Test fun unchangedMediaStoreGenerationDoesNotTriggerRedundantScan() {
        val stamp = com.domenota.medialoader.data.library.PhoneStoreStamp("volume:v1", "volume:43")
        assertTrue(maySkipUnchangedMediaNotification(stamp, stamp, true, false))
        assertFalse(maySkipUnchangedMediaNotification(stamp,
            stamp.copy(generation = "volume:44"), true, false))
        assertFalse(maySkipUnchangedMediaNotification(stamp,
            stamp.copy(versions = "volume:v2"), true, false))
        assertFalse(maySkipUnchangedMediaNotification(stamp, stamp, false, false))
        // A selected-only Android grant may change visible media without incrementing generation.
        assertFalse(maySkipUnchangedMediaNotification(stamp, stamp, true, true))
        assertFalse(maySkipUnchangedMediaNotification(null, stamp, true, false))
        assertFalse(maySkipUnchangedMediaNotification(stamp.copy(generation = null),
            stamp.copy(generation = null), true, false))
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

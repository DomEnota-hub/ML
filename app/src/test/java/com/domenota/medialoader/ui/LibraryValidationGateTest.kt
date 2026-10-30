package com.domenota.medialoader.ui

import com.domenota.medialoader.data.library.AppMode
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

    @Test fun routinePhoneRefreshRetainsExistingGalleryWithoutAnyVisibleLoadingState() {
        val visible = PhoneLibraryState(loaded = true, revision = 12, requiresValidation = false)
        val canonical = LibraryIndex(ready = true, galleryRevision = 12)
        assertTrue(canRefreshLibrarySilently(visible, canonical, 12, false, false, false))
        val refreshing = visible.copy(loading = false, requiresValidation = false)
        val index = LibraryIndex(ready = true, galleryRevision = 12, musicRevision = 8)
        assertFalse(visibleLibraryValidationPending(AppMode.GALLERY, index,
            refreshing, PhoneLibraryState()))
        assertFalse(phoneValidationPending(index, refreshing, PhoneLibraryState()))
    }

    @Test fun permissionAndPrivateMediaChangesStillRequireValidation() {
        val retained = PhoneLibraryState(loaded = true)
        val canonical = LibraryIndex(ready = true, galleryRevision = 0)
        assertFalse(canRefreshLibrarySilently(PhoneLibraryState(), canonical, 0, false, false, false))
        assertFalse(canRefreshLibrarySilently(retained, canonical, 0, true, false, false))
        assertFalse(canRefreshLibrarySilently(retained, canonical, 0, false, true, false))
        assertFalse(canRefreshLibrarySilently(retained, canonical, 0, false, false, true))
        assertFalse(canRefreshLibrarySilently(retained, LibraryIndex(), 0, false, false, false))
        assertFalse(canRefreshLibrarySilently(retained.copy(revision = 1), canonical, 0, false, false, false))
        assertTrue(canRefreshLibrarySilently(retained, canonical, 0, false, false, false))
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

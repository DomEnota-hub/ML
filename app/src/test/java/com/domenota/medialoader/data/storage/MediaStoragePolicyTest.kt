package com.domenota.medialoader.data.storage

import com.domenota.medialoader.core.model.MediaType

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoragePolicyTest {
    @Test fun photosAndVideosGoIntoPicturesAndAudioGoesIntoMusic() {
        val dest = "Pictures/MediaLoader/"
        assertEquals(dest, MediaStoragePolicy.route(dest, "image/jpeg"))
        assertEquals(dest, MediaStoragePolicy.route(dest, "image/png"))
        assertEquals(dest, MediaStoragePolicy.route(dest, "video/mp4"))
        assertEquals("Music/MediaLoader/",
            MediaStoragePolicy.route(dest, "audio/mpeg"))
        assertEquals("Music/MediaLoader/",
            MediaStoragePolicy.route(dest, "audio/mp4"))
        assertEquals("Download/MediaLoader/",
            MediaStoragePolicy.route(dest, "application/zip"))
    }

    @Test fun legacyDownloadPreferenceBecomesNewDestinationButNotOldHistory() {
        assertEquals("Pictures/MediaLoader/",
            MediaStoragePolicy.upgradePreference(null))
        assertEquals("Pictures/MediaLoader/",
            MediaStoragePolicy.upgradePreference("Download/MediaLoader/"))
        assertEquals("Pictures/Another Folder/",
            MediaStoragePolicy.upgradePreference("Download/Another Folder/"))
        assertEquals("Pictures/MyPhotos/",
            MediaStoragePolicy.upgradePreference("Pictures/MyPhotos/"))
        assertEquals("Pictures/MediaLoader/",
            MediaStoragePolicy.upgradePreference("Download/../private/"))
        assertTrue(MediaStoragePolicy.validPictures("Pictures/MediaLoader/"))
        assertFalse(MediaStoragePolicy.validPictures("Pictures/../Downloads/"))
        assertFalse(MediaStoragePolicy.validPictures("Download/MediaLoader/"))
    }

    @Test fun relativeSystemDestinationKeepsFolderAndName() {
        assertEquals("MediaLoader/clip.mp4",
            MediaStoragePolicy.subPath("Pictures/MediaLoader/", "clip.mp4"))
        assertEquals("Pictures",
            MediaStoragePolicy.root("Pictures/MediaLoader/"))
    }

    @Test fun safeMigrationRecognizesOnlyLegacyPaths() {
        assertTrue(LegacyMediaMigration.isLegacyPath("Download/MediaLoader/clip.mp4"))
        assertTrue(LegacyMediaMigration.isLegacyPath("Download/Another/photo.png"))
        assertFalse(LegacyMediaMigration.isLegacyPath("Pictures/MediaLoader/photo.jpg"))
        assertFalse(LegacyMediaMigration.isLegacyPath("Music/MediaLoader/sound.mp3"))
        assertFalse(LegacyMediaMigration.isLegacyPath("Download/"))
        assertFalse(LegacyMediaMigration.isLegacyPath("Download/../private/file"))
        assertFalse(LegacyMediaMigration.isLegacyPath(null))
    }

    @Test fun legacyMigrationPreservesActualFormatRatherThanCallingEverythingJpegMp4Mp3() {
        assertEquals("image/gif", MediaStoragePolicy.legacyMime(
            MediaType.PHOTO, "анимация.GIF", "application/octet-stream"))
        assertEquals("image/heic", MediaStoragePolicy.legacyMime(
            MediaType.PHOTO, "2026.heic", "image/heic"))
        assertEquals("image/webp", MediaStoragePolicy.legacyMime(
            MediaType.PHOTO, "preview.webp", null))
        assertEquals("video/webm", MediaStoragePolicy.legacyMime(
            MediaType.VIDEO, "movie.WEBM", "video/webm"))
        assertEquals("video/quicktime", MediaStoragePolicy.legacyMime(
            MediaType.VIDEO, "clip.mov", "application/octet-stream"))
        assertEquals("audio/flac", MediaStoragePolicy.legacyMime(
            MediaType.AUDIO, "Песня.FLAC", "audio/flac"))
        assertEquals("audio/mp4", MediaStoragePolicy.legacyMime(
            MediaType.AUDIO, "track.m4a", null))
    }

    @Test fun legacyMigrationDoesNotInventTypeForUnknownOrConflictingFiles() {
        assertEquals(null, MediaStoragePolicy.legacyMime(
            MediaType.PHOTO, "unidentified.bin", "application/octet-stream"))
        assertEquals(null, MediaStoragePolicy.legacyMime(
            MediaType.VIDEO, "clip.mp4", "image/png"))
        assertEquals(null, MediaStoragePolicy.legacyMime(
            MediaType.AUDIO, "track.jpg", null))
        assertEquals(null, MediaStoragePolicy.legacyMime(
            MediaType.PHOTO, "image", null))
        assertEquals("image/png", MediaStoragePolicy.legacyMime(
            MediaType.PHOTO, "image", "image/png; charset=binary"))
    }

}

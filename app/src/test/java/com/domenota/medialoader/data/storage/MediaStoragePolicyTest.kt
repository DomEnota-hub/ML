package com.domenota.medialoader.data.storage

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
}

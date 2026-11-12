package com.domenota.medialoader.data.storage

import com.domenota.medialoader.core.model.MediaType
import org.junit.Assert.*
import org.junit.Test

class OldDownloadsPolicyTest {
    @Test fun confirmedCurrentFolderSourcesOnly() {
        assertNull(OldDownloadsPolicy.confirmedProvider("Instagram_2.mp4", MediaType.VIDEO, false))
        assertEquals("youtube", OldDownloadsPolicy.confirmedProvider(OldDownloadsPolicy.ADDICT, MediaType.VIDEO, true))
        assertEquals("youtube", OldDownloadsPolicy.confirmedProvider(OldDownloadsPolicy.COTTON, MediaType.AUDIO, true))
        assertEquals("instagram", OldDownloadsPolicy.confirmedProvider("VID_20261018_234310.mp4", MediaType.VIDEO, true))
        assertEquals("instagram", OldDownloadsPolicy.confirmedProvider("MVIMG_20261105_190114.jpg", MediaType.PHOTO, true))
        assertEquals("recovered", OldDownloadsPolicy.confirmedProvider("DAMAGE (1).mp3", MediaType.AUDIO, true))
        assertNull(OldDownloadsPolicy.confirmedProvider(OldDownloadsPolicy.EXCLUDED, MediaType.PHOTO, true))
    }
    @Test fun firstTransferExactlyOneAndMassDoesNotMergeDistinctFiles() {
        assertFalse(OldDownloadsPolicy.validSelection(true, emptyList()))
        assertTrue(OldDownloadsPolicy.validSelection(true, listOf("a")))
        assertFalse(OldDownloadsPolicy.validSelection(true, listOf("a", "b")))
        assertFalse(OldDownloadsPolicy.validSelection(false, listOf("a", "a")))
        assertTrue(OldDownloadsPolicy.validSelection(false, listOf("a", "b")))
    }
    @Test fun mediaCollectionsAndNamesFailClosed() {
        assertEquals("Pictures/MediaLoader/", OldDownloadsPolicy.target(MediaType.PHOTO))
        assertEquals("Pictures/MediaLoader/", OldDownloadsPolicy.target(MediaType.VIDEO))
        assertEquals("Music/MediaLoader/", OldDownloadsPolicy.target(MediaType.AUDIO))
        assertTrue(OldDownloadsPolicy.safeName("DAMAGE - (2).mp3"))
        listOf("", "..", "../x.mp3", "a\\b.jpg", "a\n.jpg").forEach { assertFalse(OldDownloadsPolicy.safeName(it)) }
    }
    @Test fun everyMissingMetadataConditionHasAnIndividualReason() {
        assertTrue(OldDownloadsPolicy.metadataProblems("a.jpg", true, 100, 1, 2, 0, 0, true).isEmpty())
        val errors = OldDownloadsPolicy.metadataProblems("../a.jpg", false, 0, 0, 0, null, null, true)
        assertEquals(7, errors.size)
        assertTrue(errors.any { "DATE_ADDED" in it })
        assertTrue(errors.any { "DATE_MODIFIED" in it })
        assertTrue(errors.any { "IS_PENDING=null" in it })
        assertTrue(errors.any { "IS_TRASHED=null" in it })
        assertTrue(OldDownloadsPolicy.metadataProblems("a.jpg", true, 100, 1, 2, 0, null, false).isEmpty())
        assertFalse(OldDownloadsPolicy.metadataProblems("a.jpg", true, 100, 1, 2, 1, 0, true).isEmpty())
    }
}

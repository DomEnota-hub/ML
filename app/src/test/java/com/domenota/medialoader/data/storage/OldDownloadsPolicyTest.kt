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
}

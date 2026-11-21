package com.domenota.medialoader.data.archive

import com.domenota.medialoader.core.model.MediaType
import org.junit.Assert.*
import org.junit.Test

internal class SimpleArchivePolicyTest {
    private fun entry(id: String, uri: String, type: MediaType = MediaType.PHOTO,
        size: Long = 100) = SimpleArchiveRecord(id, uri, type, "file", ArchiveEntrySource.GALLERY, size, 1L)
    private fun candidate(id: String, uri: String, type: MediaType = MediaType.PHOTO,
        size: Long = 100) = SimpleArchiveCandidate(id, uri, type, size)

    @Test fun selectedFilesOnlyAreHiddenAndOtherMembersOfSamePhysicalFolderAreUnaffected() {
        val archive = SimpleArchivePolicy.archive(emptyList(), listOf(
            entry("phone:image:6", "content://media/external/images/media/6")))
        assertTrue(SimpleArchivePolicy.isArchived(archive,
            candidate("phone:image:6", "content://media/external/images/media/6")))
        assertFalse(SimpleArchivePolicy.isArchived(archive,
            candidate("phone:image:7", "content://media/external/images/media/7")))
    }

    @Test fun sameUriAcrossPhoneAndDownloadAliasIsOneLogicalFile() {
        val uri = "content://media/external/audio/media/12"
        val archive = SimpleArchivePolicy.archive(listOf(entry("download-1", uri, MediaType.AUDIO)),
            listOf(entry("phone:audio:12", uri, MediaType.AUDIO)))
        assertEquals(1, archive.size)
        assertTrue(SimpleArchivePolicy.isArchived(archive,
            candidate("phone:audio:12", uri, MediaType.AUDIO)))
    }

    @Test fun recycledIdDifferentUriAndSameNameDoNotAccidentallyHideNewFile() {
        val archive = listOf(entry("phone:image:42", "content://media/external/images/media/42"))
        assertFalse(SimpleArchivePolicy.isArchived(archive,
            candidate("phone:image:42", "content://media/external/images/media/84")))
        assertFalse(SimpleArchivePolicy.isArchived(archive,
            candidate("phone:image:84", "content://media/external/images/media/84")))
    }

    @Test fun changedFileSizeRequiresExplicitReconciliation() {
        val archive = listOf(entry("a", "content://media/external/audio/media/5", MediaType.AUDIO, 100))
        assertFalse(SimpleArchivePolicy.isArchived(archive,
            candidate("a", "content://media/external/audio/media/5", MediaType.AUDIO, 500)))
    }

    @Test fun duplicateCatalogIdWithDifferentBytesMustNotCorruptPersistedIndex() {
        val original = entry("phone:audio:8", "content://media/external/audio/media/8", MediaType.AUDIO, 100)
        val replacement = entry("phone:audio:8", "content://media/external/audio/media/8", MediaType.AUDIO, 900)
        assertThrows(IllegalStateException::class.java) {
            SimpleArchivePolicy.archive(listOf(original), listOf(replacement))
        }
    }

    @Test fun restoreChangesOnlySelectedEntryAndNeverTouchesOriginalFiles() {
        val before = listOf(entry("one", "content://a"), entry("two", "content://b"))
        assertEquals(listOf(before[1]), SimpleArchivePolicy.restore(before, setOf("one")))
        assertEquals(before, SimpleArchivePolicy.restore(before, emptySet()))
    }
}
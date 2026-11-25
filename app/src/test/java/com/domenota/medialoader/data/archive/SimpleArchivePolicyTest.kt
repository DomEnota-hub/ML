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

    @Test fun sixOf866OnlyAreArchivedAndRestoredWithVirtualFolderMetadata() {
        val folder = SimpleArchiveGroup(SimpleArchiveGroupKind.VIRTUAL_FOLDER, "folder-1", "Тест")
        val library = (1..866).map { entry("item-$it", "content://media/external/images/media/$it") }
        val chosen = library.take(6).map { it.copy(group = folder) }
        val archived = SimpleArchivePolicy.archive(emptyList(), chosen)
        assertEquals(6, archived.size)
        assertTrue(archived.all { it.group == folder })
        assertEquals(860, library.count { !SimpleArchivePolicy.isArchived(archived,
            candidate(it.catalogId, it.savedUri)) })
        val partial = SimpleArchivePolicy.restore(archived, setOf("item-1"))
        assertEquals(5, partial.size)
        assertTrue(partial.all { it.group == folder })
        assertTrue(SimpleArchivePolicy.restore(partial, partial.map { it.catalogId }.toSet()).isEmpty())
    }

    @Test fun conflictingAliasWithSameUriAndDifferentSizeRejectsWholeBatch() {
        val before = listOf(entry("old", "content://old"))
        val selected = listOf(entry("new", "content://new"),
            entry("alias-a", "content://shared"), entry("alias-b", "content://shared", size = 200))
        assertThrows(IllegalStateException::class.java) { SimpleArchivePolicy.archive(before, selected) }
        assertEquals(listOf(entry("old", "content://old")), before)
    }

    @Test fun repeatingPublicationSelectionDoesNotDuplicateRecordsOrLoseGroup() {
        val group = SimpleArchiveGroup(SimpleArchiveGroupKind.PUBLICATION, "post-1", "Публикация")
        val first = entry("download-a", "content://a").copy(group = group)
        val second = entry("download-b", "content://b").copy(group = group)
        val before = SimpleArchivePolicy.archive(emptyList(), listOf(first, second))
        val after = SimpleArchivePolicy.archive(before, listOf(first.copy(catalogId = "phone-a"), second))
        assertEquals(before, after)
    }

    @Test fun unknownSizeAliasCannotMaskContradictoryKnownSizes() {
        val unknown = entry("unknown", "content://shared").copy(fileSizeBytes = null)
        val one = entry("one", "content://shared", size = 100)
        val two = entry("two", "content://shared", size = 200)
        assertThrows(IllegalStateException::class.java) {
            SimpleArchivePolicy.archive(emptyList(), listOf(unknown, one, two))
        }
    }
    @Test fun folderDisappearsOnlyWhenEveryMemberIsArchivedAndReturnsOnPartialRestore() {
        val one = entry("one", "content://one")
        val two = entry("two", "content://two")
        val members = mapOf(
            "full" to listOf(candidate("one", "content://one"), candidate("two", "content://two")),
            "partial" to listOf(candidate("one", "content://one"), candidate("three", "content://three")),
            "empty" to emptyList(),
            "unknown" to listOf(candidate("one", "content://one"), null),
            "changed" to listOf(candidate("one", "content://one", size = 999)),
            "reused" to listOf(candidate("one", "content://different"))
        )
        val archived = listOf(one, two)
        assertEquals(setOf("full"), SimpleArchivePolicy.fullyArchivedFolderIds(archived, members))
        assertTrue(SimpleArchivePolicy.fullyArchivedFolderIds(
            SimpleArchivePolicy.restore(archived, setOf("one")), members).isEmpty())
        assertTrue(SimpleArchivePolicy.fullyArchivedFolderIds(emptyList(), members).isEmpty())
    }

}

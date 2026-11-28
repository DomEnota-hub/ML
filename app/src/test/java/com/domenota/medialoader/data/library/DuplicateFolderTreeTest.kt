package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import org.junit.Assert.*
import org.junit.Test

class DuplicateFolderTreeTest {
    private fun audio(id: String, directory: String, volume: String = "external_primary") =
        DownloadEntity(id, "device", "$id.mp3", MediaType.AUDIO, DownloadState.COMPLETED,
            "content://media/audio/$id", 1000L, 1L, groupId = "$volume:$directory/")

    private fun photo(id: String, bucket: String, album: String) =
        DownloadEntity(id, "device", "$id.jpg", MediaType.PHOTO, DownloadState.COMPLETED,
            "content://media/images/$id", 2000L, 1L, bulkMediaKey = bucket, groupId = album)

    @Test fun verifiedMusicPathsShareRealAncestorsAndKeepExactFolderScope() {
        val one = audio("one", "Download/Packs/One")
        val two = audio("two", "Download/Packs/Two")
        val three = audio("three", "Download/Packs/Two")
        val options = DuplicateFolderScope.options(listOf(one, two, three))
        val root = DuplicateFolderTree.build(options).single()
        assertEquals("Download", root.name)
        assertEquals(3, root.fileCount)
        assertEquals(3000L, root.sizeBytes)
        val packs = root.children.single()
        assertEquals("Packs", packs.name)
        assertEquals(2, packs.children.size)
        assertEquals(2, packs.folderCount)
        val onlyOne = packs.children.first { it.name == "One" }.allKeys
        assertEquals(setOf(DuplicateFolderScope.key(one)), onlyOne)
        assertEquals(listOf(one), DuplicateFolderScope.filter(listOf(one, two, three), onlyOne) { it })
        assertEquals(3, DuplicateFolderScope.filter(listOf(one, two, three), packs.allKeys) { it }.size)
    }

    @Test fun galleryBucketsStaySeparateWithoutVerifiedPathsThenNestByRealLocation() {
        val a = photo("a", "bucket-a", "2026")
        val b = photo("b", "bucket-b", "2026")
        val options = DuplicateFolderScope.options(listOf(a, b))
        assertTrue(options.all { it.directoryId == null })
        assertEquals(2, DuplicateFolderTree.build(options).size)
        val verified = DuplicateFolderPathResolver.apply(options,
            mapOf("bucket:bucket-a" to "volume:external_primary|DCIM/Camera/Alpha",
                "bucket:bucket-b" to "volume:external_primary|DCIM/Camera/Beta"))
        val root = DuplicateFolderTree.build(verified).single()
        assertEquals("DCIM", root.name)
        assertEquals(2, root.fileCount)
        assertEquals(2, root.allKeys.size)
        assertEquals(listOf("Alpha", "Beta"), root.children.single().children.map { it.name })
    }

    @Test fun sameNamesOnDifferentVolumesAndUnknownBucketsAreNeverMerged() {
        val a = audio("a", "Music/Shared", "external_primary")
        val b = audio("b", "Music/Shared", "external_secondary")
        val unknown = photo("unknown", "bucket-unknown", "Shared")
        val roots = DuplicateFolderTree.build(DuplicateFolderScope.options(listOf(a, b, unknown)))
        assertEquals(3, roots.size)
        assertEquals(3, roots.map { it.id }.toSet().size)
        assertEquals(1, roots.count { it.name == "Shared" && it.children.isEmpty() })
        assertEquals(1, roots.count { it.id == "folder:bucket:bucket-unknown" })
    }

    @Test fun allSortModesWorkWithinParentAndUnknownSizesAlwaysGoLast() {
        val options = listOf(
            DuplicateFolderOption("alpha", "Alpha", "Music/Alpha", 3, 300L,
                "volume:external_primary|Music/Alpha"),
            DuplicateFolderOption("beta", "Beta", "Music/Beta", 7, 800L,
                "volume:external_primary|Music/Beta"),
            DuplicateFolderOption("gamma", "Gamma", "Music/Gamma", 1, null,
                "volume:external_primary|Music/Gamma"),
        )
        val root = DuplicateFolderTree.build(options)
        fun names(mode: DuplicateFolderSort, descending: Boolean) =
            DuplicateFolderTree.sort(root, mode, descending).single().children.map { it.name }
        assertEquals(listOf("Beta", "Alpha", "Gamma"), names(DuplicateFolderSort.FILE_COUNT, true))
        assertEquals(listOf("Gamma", "Alpha", "Beta"), names(DuplicateFolderSort.FILE_COUNT, false))
        assertEquals(listOf("Alpha", "Beta", "Gamma"), names(DuplicateFolderSort.NAME, false))
        assertEquals(listOf("Beta", "Alpha", "Gamma"), names(DuplicateFolderSort.SIZE, true))
        assertEquals(listOf("Alpha", "Beta", "Gamma"), names(DuplicateFolderSort.SIZE, false))
        assertNull(DuplicateFolderTree.sort(root, DuplicateFolderSort.SIZE, true).single().sizeBytes)
        val numbered = listOf(
            DuplicateFolderOption("two", "Folder2", "Folder2", 1),
            DuplicateFolderOption("ten", "Folder10", "Folder10", 1),
            DuplicateFolderOption("one", "Folder1", "Folder1", 1),
        )
        assertEquals(listOf("Folder1", "Folder2", "Folder10"),
            DuplicateFolderTree.sort(DuplicateFolderTree.build(numbered),
                DuplicateFolderSort.NAME, false).map { it.name })
    }

    @Test fun searchShowsAncestorsButNeverSelectsUnmatchedSibling() {
        val a = audio("a", "Music/Alpha")
        val b = audio("b", "Music/Beta")
        val tree = DuplicateFolderTree.build(DuplicateFolderScope.options(listOf(a, b)))
        val displayed = DuplicateFolderTree.visible(tree, emptySet(), "Alpha").map { it.node.name }
        assertEquals(listOf("Music", "Alpha"), displayed)
        assertEquals(setOf(DuplicateFolderScope.key(a)), DuplicateFolderTree.matchingKeys(tree, "Alpha"))
        assertEquals(setOf(DuplicateFolderScope.key(a), DuplicateFolderScope.key(b)),
            DuplicateFolderTree.matchingKeys(tree, "Music"))
        assertEquals(listOf("Music"), DuplicateFolderTree.visible(tree, emptySet(), "").map { it.node.name })
    }

    @Test fun nestedCountExcludesCurrentFolderAndIncludesActualIntermediateDescendants() {
        val root = audio("root", "Music")
        val child = audio("child", "Music/Child")
        val grandchild = audio("grandchild", "Music/Child/Grandchild")
        val tree = DuplicateFolderTree.build(DuplicateFolderScope.options(listOf(root, child, grandchild))).single()
        assertEquals(3, tree.fileCount)
        assertEquals(2, tree.folderCount)
        assertEquals(1, tree.children.single().folderCount)
        assertEquals(0, tree.children.single().children.single().folderCount)
    }

    @Test fun totalSizeIsUnknownIfAnyNestedPartIsMissingOrOverflows() {
        val root = DuplicateFolderTree.build(listOf(
            DuplicateFolderOption("a", "A", "Music/A", 1, Long.MAX_VALUE,
                "volume:external_primary|Music/A"),
            DuplicateFolderOption("b", "B", "Music/B", 1, 1L,
                "volume:external_primary|Music/B"),
        )).single()
        assertNull(root.sizeBytes)
        assertEquals(2, root.fileCount)
        val missing = DuplicateFolderTree.build(listOf(
            DuplicateFolderOption("a", "A", "Music/A", 1, null,
                "volume:external_primary|Music/A"),
            DuplicateFolderOption("b", "B", "Music/B", 1, 1L,
                "volume:external_primary|Music/B"),
        )).single()
        assertNull(missing.sizeBytes)
    }

    @Test fun resolvedPathsRejectForeignKeysAndUnsafeVolumeAndSegments() {
        val raw = DuplicateFolderOption("bucket:x", "Camera", "Расположение не определено", 1, 100L)
        val notBucket = raw.copy(key = "group:PHOTO:x")
        assertEquals(notBucket, DuplicateFolderPathResolver.apply(listOf(notBucket),
            mapOf("group:PHOTO:x" to "volume:external_primary|DCIM/Camera")).single())
        for (path in listOf("volume:external_primary|DCIM/../Camera",
                "volume:external_primary|DCIM//Camera",
                "volume:external/primary|DCIM/Camera",
                "file|DCIM/Camera")) {
            assertEquals(raw, DuplicateFolderPathResolver.apply(listOf(raw),
                mapOf("bucket:x" to path)).single())
        }
        val verified = DuplicateFolderPathResolver.apply(listOf(raw),
            mapOf("bucket:x" to "volume:external_primary|DCIM/Camera")).single()
        assertEquals("volume:external_primary|DCIM/Camera", verified.directoryId)
    }

    @Test fun mediaStoreRelativePathMustNotBeAbsoluteOrSilentlyRepaired() {
        val valid = DuplicateFolderPathResolver.verifiedDirectory("external_primary", "DCIM/Camera/")
        assertEquals("volume:external_primary|DCIM/Camera",valid)
        val invalid = listOf("/DCIM/Camera", "DCIM//Camera", "DCIM/Camera//",
            "DCIM/../Camera", "DCIM/./Camera", "DCIM\\Camera", "DCIM/Camera|Another", "")
        assertTrue(invalid.all { DuplicateFolderPathResolver.verifiedDirectory("external_primary",it)==null })
        assertNull(DuplicateFolderPathResolver.verifiedDirectory("external/primary","DCIM/Camera/"))
        assertNull(DuplicateFolderPathResolver.verifiedDirectory(null,"DCIM/Camera/"))
        val raw = DuplicateFolderOption("bucket:x","Camera","Расположение не определено",1,100L)
        assertEquals(raw,DuplicateFolderPathResolver.apply(listOf(raw),
            mapOf("bucket:x" to "volume:external_primary|/DCIM/Camera")).single())
    }

    @Test fun unsafeOrUnknownPathNeverCreatesSyntheticParent() {
        val a = DuplicateFolderOption("a", "A", "A", 1, 100L,
            "volume:external_primary|DCIM/../Other/A")
        val b = DuplicateFolderOption("b", "B", "Путь не определён", 1, null)
        val nodes = DuplicateFolderTree.build(listOf(a, b))
        assertEquals(2, nodes.size)
        assertEquals(listOf("A", "B"), nodes.map { it.name })
        val ignored = DuplicateFolderPathResolver.apply(listOf(b),
            mapOf("b" to "volume:external_primary|DCIM/../Other"))
        assertEquals(b, ignored.single())
    }
}

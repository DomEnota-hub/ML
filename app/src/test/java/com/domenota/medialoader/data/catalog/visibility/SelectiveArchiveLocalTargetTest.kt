package com.domenota.medialoader.data.catalog.visibility

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SelectiveArchiveLocalTargetTest {
    private val token="T".repeat(32)
    private val origin=SelectiveArchiveIdentity(
        "id","content://media/external/audio/media/99",99L,
        "external_primary","Music/Shared/","safe.mp3",4L,
        "a".repeat(64),1_700_000_000_000L,"b".repeat(64))
    private val receipt=SelectiveArchiveMoveReceipt(
        "folder","id",origin.originalUri,origin.sha256,
        "Music/Shared/MLSelectiveArchive_$token/",token,
        SelectiveArchiveMovePhase.HIDE_INTENT,1)
    private fun fixture(run:(File,SelectiveArchiveLocalTarget)->Unit) {
        val root=Files.createTempDirectory("archive-target").toFile()
        try {
            val source=File(root,"Music/Shared")
            assertTrue(source.mkdirs())
            File(source,"safe.mp3").writeBytes(byteArrayOf(1,2,3,4))
            run(root,SelectiveArchiveLocalTarget({root},{true}))
        } finally {root.deleteRecursively()}
    }

    @Test fun ownMarkerExistsOnlyInUniqueChildAndDoesNotTouchSiblings() = fixture { root,target ->
        val source=File(root,"Music/Shared")
        val sibling=File(source,"unrelated.mp3").apply {writeText("unchanged")}
        target.ensure(origin,receipt)
        assertTrue(target.inspect(origin,receipt))
        assertTrue(target.sourceFile(origin).isFile)
        assertEquals("unchanged",sibling.readText())
        assertFalse(File(source,".nomedia").exists())
        val hidden=File(root,receipt.targetRelativePath)
        assertTrue(File(hidden,".nomedia").isFile)
        assertEquals(0L,File(hidden,".nomedia").length())
        target.ensure(origin,receipt) // Idempotent replay.
        assertEquals(setOf(".nomedia",".ml-selective-owner-v1"),
            hidden.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun foreignFolderIsNeverAdoptedOrModified() = fixture { root,target ->
        val folder=File(root,receipt.targetRelativePath)
        assertTrue(folder.mkdirs())
        val foreign=File(folder,"unrelated.txt").apply { writeText("original") }
        assertThrows(IllegalStateException::class.java) { target.ensure(origin,receipt) }
        assertEquals("original",foreign.readText())
        assertFalse(File(folder,".nomedia").exists())
        assertFalse(target.inspect(origin,receipt))
    }

    @Test fun forgedOwnerAndMarkerBlockMutation() = fixture { root,target ->
        val dir=File(root,receipt.targetRelativePath)
        assertTrue(dir.mkdirs())
        File(dir,".ml-selective-owner-v1").writeText("FOREIGN")
        File(dir,".nomedia").writeText("")
        assertThrows(IllegalStateException::class.java) { target.ensure(origin,receipt) }
        assertFalse(target.inspect(origin,receipt))
        assertEquals("FOREIGN",File(dir,".ml-selective-owner-v1").readText())
    }

    @Test fun symlinkInChildIsRejectedWithoutReadingForeignData() = fixture { root,target ->
        target.ensure(origin,receipt)
        val outside=File(root,"outside").apply {writeText("untouched")}
        val child=File(root,receipt.targetRelativePath+"safe.mp3")
        Files.createSymbolicLink(child.toPath(),outside.toPath())
        assertFalse(target.inspect(origin,receipt))
        assertThrows(IllegalStateException::class.java) { target.hiddenFile(origin,receipt) }
        assertEquals("untouched",outside.readText())
    }

    @Test fun cleanupDeletesOnlyOwnedEmptyChildAndKeepsOriginalAndBystanders() = fixture { root,target ->
        val source=File(root,"Music/Shared")
        val keeper=File(source,"keeper.mp3").apply {writeText("not a selected file")}
        target.ensure(origin,receipt)
        assertTrue(target.releaseEmpty(origin,receipt))
        assertTrue(target.releaseEmpty(origin,receipt)) // Replayed cleanup.
        assertTrue(target.sourceFile(origin).isFile)
        assertEquals("not a selected file",keeper.readText())
        assertFalse(File(root,receipt.targetRelativePath).exists())
    }

    @Test fun cleanupRejectsMediaInsideChildAndNeverRecurses() = fixture { root,target ->
        target.ensure(origin,receipt)
        val foreign=File(root,receipt.targetRelativePath+"unknown.mp3")
            .apply {writeText("do not touch")}
        assertThrows(IllegalStateException::class.java) {
            target.releaseEmpty(origin,receipt)
        }
        assertEquals("do not touch",foreign.readText())
        assertTrue(File(root,receipt.targetRelativePath+".nomedia").exists())
    }

    @Test fun differentReceiptCannotClaimTheSameTarget() = fixture { _,target ->
        val fake=receipt.copy(originalSha256="c".repeat(64))
        assertThrows(IllegalStateException::class.java) { target.ensure(origin,fake) }
        assertFalse(target.inspect(origin,fake))
    }
}

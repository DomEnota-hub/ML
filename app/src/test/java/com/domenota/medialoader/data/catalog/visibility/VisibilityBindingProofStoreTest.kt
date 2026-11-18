package com.domenota.medialoader.data.catalog.visibility

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VisibilityBindingProofStoreTest {
    @Test fun durableCasAndCompactionAcrossInstances() = runBlocking {
        val root = Files.createTempDirectory("visibility-proof").toFile()
        try {
            val store = VisibilityBindingProofStore(root)
            assertNull(store.read("op")); store.write("op",null,"initial")
            val replay = VisibilityBindingProofStore(root)
            assertEquals("initial",replay.read("op"))
            try { replay.write("op",null,"other"); fail("Stale writer") } catch (_:IllegalStateException) { }
            replay.write("op","initial","compact")
            assertEquals("compact",store.read("op"))
        } finally { root.deleteRecursively() }
    }
    @Test fun corruptionAndPathTraversalFailClosed() = runBlocking {
        val root = Files.createTempDirectory("visibility-proof").toFile()
        try {
            val store = VisibilityBindingProofStore(root)
            store.write("op",null,"initial")
            val file = File(root,"op.binding")
            val damaged = file.readBytes().also { it[0] = 0 }
            file.writeBytes(damaged)
            try { store.read("op"); fail("Corrupt proof") } catch (_:IllegalStateException) { }
            try { store.write("op",null,"replacement"); fail("Corrupt proof overwritten") } catch (_:IllegalStateException) { }
            assertTrue(file.readBytes().contentEquals(damaged))
            try { store.write("../outside",null,"bad"); fail("Path traversal") } catch (_:IllegalArgumentException) { }
            assertFalse(File(root.parentFile,"outside.binding").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun androidSystemAncestorAliasIsAcceptedWithoutFollowingProofSymlinks() = runBlocking {
        val root = Files.createTempDirectory("visibility-proof-android-alias").toFile()
        try {
            val realParent = File(root, "physical-app").apply { mkdirs() }
            val noBackup = File(realParent, "no_backup").apply { mkdirs() }
            val alias = File(root, "system-managed-alias")
            Files.createSymbolicLink(alias.toPath(), realParent.toPath())
            val viaAlias = File(File(alias, "no_backup"), "folder-visibility-bindings-v1")
            assertNotEquals(viaAlias.canonicalFile, viaAlias.absoluteFile)
            val store = VisibilityBindingProofStore(viaAlias)
            store.write("op", null, "safe-data")
            assertEquals("safe-data", store.read("op"))
            assertTrue(File(noBackup, "folder-visibility-bindings-v1/op.binding").isFile)
        } finally { root.deleteRecursively() }
    }

    @Test fun directDirectoryAndProofSymlinksRejectWithoutTouchingExternalFile() = runBlocking {
        val root = Files.createTempDirectory("visibility-proof-symlink").toFile()
        try {
            val outside = File(root, "outside").apply { mkdirs() }
            val ordinary = File(outside, "sentinel").apply { writeText("unchanged") }
            val linkedDirectory = File(root, "linked")
            Files.createSymbolicLink(linkedDirectory.toPath(), outside.toPath())
            try {
                VisibilityBindingProofStore(linkedDirectory).write("op", null, "data")
                fail("Directory symlink accepted")
            } catch (failure: VisibilityProofStoreFailure) {
                assertEquals("DIR_SYMLINK", failure.reason)
            }
            val legitimate = File(root, "proof").apply { mkdirs() }
            Files.createSymbolicLink(File(legitimate, "op.binding").toPath(), ordinary.toPath())
            try {
                VisibilityBindingProofStore(legitimate).read("op")
                fail("Proof symlink accepted")
            } catch (failure: VisibilityProofStoreFailure) {
                assertEquals("FILE_NOT_REGULAR", failure.reason)
            }
            assertEquals("unchanged", ordinary.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun corruptProofHasStableDetailedReasonWithoutOverwritingBytes() = runBlocking {
        val dir = Files.createTempDirectory("visibility-proof-corrupt").toFile()
        try {
            val store = VisibilityBindingProofStore(dir)
            store.write("op", null, "original")
            val file = File(dir, "op.binding")
            file.writeBytes(file.readBytes().also { it[0] = (it[0].toInt() xor 1).toByte() })
            val corrupted = file.readBytes()
            try { store.read("op"); fail("Checksum accepted") }
            catch (failure: VisibilityProofStoreFailure) { assertEquals("CHECKSUM", failure.reason) }
            assertArrayEquals(corrupted, file.readBytes())
        } finally { dir.deleteRecursively() }
    }
    @Test fun largeUnicodeProofRetainsExactBytesAndRejectsChangedDigestTrailer() = runBlocking {
        val root=Files.createTempDirectory("visibility-proof-unicode").toFile()
        try {
            val payload="{\"synthetic\":\""+"🦝Я".repeat(65536)+"\"}"
            val store=VisibilityBindingProofStore(root)
            store.write("unicode",null,payload)
            repeat(3) { assertEquals(payload,store.read("unicode")) }
            val file=File(root,"unicode.binding")
            val bytes=file.readBytes();bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
            file.writeBytes(bytes)
            try { store.read("unicode");fail("A changed SHA trailer must fail closed") }
            catch(_:VisibilityProofStoreFailure) { }
            assertArrayEquals(bytes,file.readBytes())
        } finally { root.deleteRecursively() }
    }

}

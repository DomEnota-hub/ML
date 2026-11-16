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
}

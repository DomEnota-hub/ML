package com.domenota.medialoader.data.catalog.visibility

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HiddenFolderFileStoreTest {
    private val token = "0123456789abcdef"
    private val bytes = "original bytes".toByteArray()
    private val date = 1700000000000L
    private suspend fun withStore(action: suspend (File, HiddenFolderFileStore) -> Unit) {
        val root = Files.createTempDirectory("ml-hidden").toFile()
        try { action(root, HiddenFolderFileStore(File(root, "hidden"))) } finally { root.deleteRecursively() }
    }
    @Test fun copiesRealBytesAndRestoresDateWithMarkerAndExactName() = runBlocking {
        withStore { root, store ->
            val file = store.target(token, "Фото 1.jpg")
            val expected = HiddenFolderFileStore.fingerprint(bytes.inputStream())
            store.copyVerified(file, expected, date) { bytes.inputStream() }
            assertTrue(File(root, "hidden/.nomedia").isFile)
            assertEquals(date, file.lastModified()); assertTrue(bytes.contentEquals(file.readBytes()))
            store.verifyLocation(file, date)
            store.copyVerified(file, expected, date) { error("Existing verified copy must be reused") }
            store.removeVerified(file); assertFalse(file.exists())
        }
    }
    @Test fun equalNamesHaveSeparateOperationDirectories() = runBlocking {
        withStore { _, store ->
            val a = store.target(token, "same.mp4")
            val b = store.target("fedcba9876543210", "same.mp4")
            assertTrue(a != b); assertEquals(a.name,b.name)
        }
    }
    @Test fun wrongCopyAndExistingCollisionNeverReplaceBytes() = runBlocking {
        withStore { _, store ->
            val file = store.target(token, "movie.mp4")
            val expected = HiddenFolderFileStore.fingerprint(bytes.inputStream())
            try { store.copyVerified(file, expected, date) { "bad".byteInputStream() }; fail("Expected rejection") }
            catch (_: IllegalStateException) { }
            assertFalse(file.exists())
            file.writeText("unrelated")
            try { store.copyVerified(file, expected, date) { bytes.inputStream() }; fail("Expected rejection") }
            catch (_: IllegalStateException) { }
            assertEquals("unrelated", file.readText())
            assertEquals(listOf(file), file.parentFile!!.listFiles()!!.toList())
        }
    }
    @Test fun refusesTraversalAndSymlinksOutsideHiddenRoot() = runBlocking {
        withStore { root, store ->
            val outside = File(root,"outside.jpg").apply { writeBytes(bytes) }
            assertFalse(store.owns(outside))
            for (name in listOf("../outside.jpg", ".nomedia", "sub/file.jpg")) {
                try { store.target(token,name); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
            }
            val link = store.target(token,"link.jpg")
            Files.createSymbolicLink(link.toPath(),outside.toPath())
            assertFalse(store.owns(link))
            try { store.removeVerified(link); fail("Expected rejection") } catch (_: IllegalStateException) { }
            assertTrue(outside.exists())
        }
    }
    @Test fun existingUnrelatedDirectoryIsNeverMarkedNoMedia() = runBlocking {
        withStore { root, store ->
            val dir = File(root,"hidden").apply { mkdirs() }
            File(dir,"unrelated.jpg").writeBytes(bytes)
            try { store.ensureRoot(); fail("Expected rejection") } catch (_: IllegalStateException) { }
            assertFalse(File(dir,".nomedia").exists()); assertTrue(bytes.contentEquals(File(dir,"unrelated.jpg").readBytes()))
        }
    }
    @Test fun corruptedMarkerBlocksPublication() = runBlocking {
        withStore { root, store ->
            val file = store.target(token,"movie.mp4")
            File(root,"hidden/.nomedia").writeText("not our marker")
            try { store.copyVerified(file, HiddenFolderFileStore.fingerprint(bytes.inputStream()),date) { bytes.inputStream() }; fail("Expected rejection") }
            catch (_: IllegalStateException) { }
            assertFalse(file.exists())
        }
    }
    @Test fun readOnlyRootProbeDistinguishesMissingDirectoryAndNonemptyMarker() = runBlocking {
        withStore { root, store ->
            val marker = File(root, "hidden/.nomedia")
            assertEquals(HiddenRootMarkerCode.MISSING, HiddenRootDiagnostic.observeNomedia(marker).code)
            store.ensureRoot()
            assertEquals(HiddenRootMarkerCode.VALID, HiddenRootDiagnostic.observeNomedia(marker).code)
            marker.writeText("unrelated data")
            val original = marker.readBytes()
            val observation = HiddenRootDiagnostic.observeNomedia(marker)
            assertEquals(HiddenRootMarkerCode.NONEMPTY, observation.code)
            assertEquals(original.size.toLong(), observation.sizeBytes)
            assertTrue(observation.extendedSummary().contains("markerType=NONEMPTY"))
            assertFalse(observation.extendedSummary().contains(marker.absolutePath))
            try { store.ensureRoot(); fail("Must refuse nonempty marker") } catch (_: IllegalStateException) { }
            assertArrayEquals(original, marker.readBytes())
            assertTrue(marker.delete())
            assertTrue(marker.mkdir())
            assertEquals(HiddenRootMarkerCode.DIRECTORY, HiddenRootDiagnostic.observeNomedia(marker).code)
            try { store.ensureRoot(); fail("Must refuse directory marker") } catch (_: IllegalStateException) { }
            assertTrue(marker.isDirectory)
        }
    }

    @Test fun readOnlyRootProbeDetectsSymlinkWithoutFollowingOrRewritingIt() = runBlocking {
        withStore { root, store ->
            store.ensureRoot()
            val marker = File(root, "hidden/.nomedia")
            val outside = File(root, "outside-nomedia").apply { writeText("leave me") }
            assertTrue(marker.delete())
            Files.createSymbolicLink(marker.toPath(), outside.toPath())
            assertEquals(HiddenRootMarkerCode.SYMBOLIC_LINK, HiddenRootDiagnostic.observeNomedia(marker).code)
            try { store.ensureRoot(); fail("Must refuse symlink") } catch (_: IllegalStateException) { }
            assertEquals("leave me", outside.readText())
            assertTrue(Files.isSymbolicLink(marker.toPath()))
        }
    }

    @Test fun ownershipMarkerSymlinkNeverChangesUnrelatedFile() = runBlocking {
        withStore { root, store ->
            val outside = File(root,"outside-marker").apply { writeBytes(bytes) }
            val dir = File(root,"hidden").apply { mkdirs() }
            Files.createSymbolicLink(File(dir,".ml-folder-visibility-v1").toPath(),outside.toPath())
            try { store.ensureRoot(); fail("Expected rejection") } catch (_: IllegalStateException) { }
            assertTrue(bytes.contentEquals(outside.readBytes()))
            assertFalse(File(dir,".nomedia").exists())
        }
    }

}

package com.domenota.medialoader.data.catalog.visibility

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FileVisibilityMoveJournalTest {
    private val record = VisibilityMoveRecord("op", "folder", "download", VisibilityDirection.HIDE,
        "content://media/external/images/media/17", "Фото.jpg", 12345L,
        VisibilityFingerprint(10L, "a".repeat(64)), "token")

    @Test fun journalPersistsAcrossInstancesAndClearsAtomically() = runBlocking {
        val root = Files.createTempDirectory("visibility-test").toFile()
        try {
            val file = File(root, "journal")
            val first = FileVisibilityMoveJournal(file)
            assertNull(first.read()); first.replace(null, record)
            val second = FileVisibilityMoveJournal(file)
            assertEquals(record, second.read())
            val linked = record.copy(destinationUri = "file:///hidden/17.jpg", phase = VisibilityMovePhase.LINKED)
            second.replace(record, linked)
            assertEquals(linked, first.read())
            first.replace(linked, null)
            assertTrue(file.isFile)
            assertNull(FileVisibilityMoveJournal(file).read())
        } finally { root.deleteRecursively() }
    }

    @Test fun staleWriterCannotReplaceJournal() = runBlocking {
        val root = Files.createTempDirectory("visibility-test").toFile()
        try {
            val j = FileVisibilityMoveJournal(File(root,"journal"))
            j.replace(null, record)
            try { j.replace(null, record.copy(operationId = "other")); fail("CAS must fail") }
            catch (_: IllegalStateException) { }
            assertEquals(record, j.read())
        } finally { root.deleteRecursively() }
    }

    @Test fun corruptOrTruncatedJournalCannotBeReplacedAsEmpty() = runBlocking {
        val root = Files.createTempDirectory("visibility-test").toFile()
        try {
            val file = File(root,"journal")
            val j = FileVisibilityMoveJournal(file)
            for (bytes in listOf(byteArrayOf(), VisibilityJournalCodec.encode(record).also { it[10] = (it[10] + 1).toByte() }, ByteArray(VisibilityJournalCodec.MAX_BYTES + 1))) {
                file.writeBytes(bytes)
                try { j.read(); fail("Must not interpret corruption as absence") }
                catch (_: IllegalStateException) { }
                try { j.replace(null, record); fail("Must not overwrite corrupt journal") }
                catch (_: IllegalStateException) { }
                assertTrue(bytes.contentEquals(file.readBytes()))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun everyPhaseAndDirectionRoundTrips() {
        for (phase in VisibilityMovePhase.values()) for (direction in VisibilityDirection.values()) {
            val next = record.copy(direction = direction, phase = phase,
                destinationUri = if (phase == VisibilityMovePhase.PREPARED) null else "file:///hidden/17.jpg")
            assertEquals(next, VisibilityJournalCodec.decode(VisibilityJournalCodec.encode(next)))
        }
        assertNull(VisibilityJournalCodec.decode(VisibilityJournalCodec.encode(null)))
    }
}

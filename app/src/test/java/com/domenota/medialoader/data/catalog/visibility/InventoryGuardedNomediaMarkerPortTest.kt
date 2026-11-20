package com.domenota.medialoader.data.catalog.visibility

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class InventoryGuardedNomediaMarkerPortTest {
    private val tree = "content://com.android.externalstorage.documents/tree/primary%3APictures%2FAlbum"
    private val expected = listOf(NomediaMediaSignature("selected.mp3", 7L))

    private class Port : NomediaMarkerPort {
        var created = 0
        var marker: NomediaMarkerProof? = null
        override suspend fun inspect(treeUri: String) = marker
        override suspend fun createEmpty(treeUri: String): NomediaMarkerProof {
            created++
            return NomediaMarkerProof("owned", 0).also { marker = it }
        }
        override suspend fun deleteVerified(treeUri: String, documentId: String): Boolean {
            check(marker?.documentId == documentId)
            marker = null
            return true
        }
    }

    private class Store : NomediaFolderStore {
        var state = NomediaFolderSnapshot()
        override fun snapshot() = state
        override fun write(record: NomediaFolderRecord) {
            state = state.copy(folders = state.folders + (record.folderId to record))
        }
    }

    private fun entry(name: String, size: Long?) =
        NomediaSafEntry("id-$name", name, "audio/mpeg", size)

    @Test fun unchangedExactInventoryCreatesOneMarker() = runBlocking {
        val port = Port()
        val guard = InventoryGuardedNomediaMarkerPort(port, expected) {
            listOf(entry("selected.mp3", 7L))
        }
        val state = Store()
        val record = NomediaFolderEngine(state, guard).hide("folder", tree)
        assertEquals(NomediaMarkerState.OWN_MARKER, record.markerState)
        assertEquals(1, port.created)
    }

    @Test fun additionalPhysicalMediaAtCommitTimePreventsMarker() = runBlocking {
        val port = Port()
        val guard = InventoryGuardedNomediaMarkerPort(port, expected) {
            listOf(entry("selected.mp3", 7L), entry("unrelated.mp3", 11L))
        }
        val state = Store()
        assertThrows(IllegalStateException::class.java) {
            runBlocking { NomediaFolderEngine(state, guard).hide("folder", tree) }
        }
        assertEquals(0, port.created)
        assertNull(port.marker)
        assertEquals(NomediaMarkerState.FAILED, state.snapshot().folders["folder"]?.markerState)
    }

    @Test fun unknownSizeOrChangedFileBlocksWrite() = runBlocking {
        for (current in listOf(
            listOf(entry("selected.mp3", null)),
            listOf(entry("selected.mp3", 8L)),
            emptyList()
        )) {
            val port = Port()
            val guard = InventoryGuardedNomediaMarkerPort(port, expected) { current }
            assertThrows(IllegalStateException::class.java) {
                runBlocking { guard.createEmpty(tree) }
            }
            assertEquals(0, port.created)
        }
    }

    @Test fun virtualSixFrom806StillCannotCreateMarker() = runBlocking {
        val port = Port()
        val six = (1..6).map { NomediaMediaSignature("track-$it.mp3", 1L) }
        val guard = InventoryGuardedNomediaMarkerPort(port, six) {
            (1..806).map { entry("track-$it.mp3", 1L) }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { guard.createEmpty(tree) }
        }
        assertEquals(0, port.created)
    }
}

package com.domenota.medialoader.data.catalog.visibility

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NomediaFolderEngineTest {
    private class MemoryStore(
        var state:NomediaFolderSnapshot=NomediaFolderSnapshot(),
    ):NomediaFolderStore {
        val writes=mutableListOf<NomediaFolderRecord>()
        override fun snapshot()=state
        override fun write(record:NomediaFolderRecord) {
            writes+=record
            state=state.copy(folders=state.folders+(record.folderId to record))
        }
    }
    private class MarkerPort:NomediaMarkerPort {
        var marker:NomediaMarkerProof?=null
        var creates=0
        var deletes=0
        var deny=false
        var failCreate=false
        var mutateBeforeDelete=false
        override suspend fun inspect(treeUri:String):NomediaMarkerProof? {
            check(!deny) { "Permission revoked" }
            return marker
        }
        override suspend fun createEmpty(treeUri:String):NomediaMarkerProof {
            creates++
            if(failCreate) error("Disk full after marker command")
            check(marker==null)
            return NomediaMarkerProof("marker-1",0).also { marker=it }
        }
        override suspend fun deleteVerified(treeUri:String,documentId:String):Boolean {
            deletes++
            if(mutateBeforeDelete) marker=NomediaMarkerProof("attacker-replaced",0)
            check(marker?.documentId==documentId && marker?.bytes==0L)
            marker=null
            return true
        }
    }
    private val tree="content://com.android.externalstorage.documents/tree/primary%3ATest"
    private fun engine(s:MemoryStore,p:MarkerPort)=NomediaFolderEngine(s,p){1234L}

    @Test fun createsOnlyMarkerThenDeletesOnlySameOwnedMarker()=runBlocking {
        val store=MemoryStore();val port=MarkerPort()
        val h=engine(store,port).hide("album-1",tree)
        assertTrue(h.excludedFromMl)
        assertEquals(NomediaMarkerState.OWN_MARKER,h.markerState)
        assertEquals("marker-1",h.ownedDocumentId)
        assertEquals(1,port.creates)
        assertEquals(NomediaMarkerState.PENDING_HIDE,store.writes.first().markerState)
        val visible=engine(store,port).show("album-1")
        assertFalse(visible.excludedFromMl)
        assertNull(visible.ownedDocumentId)
        assertEquals(NomediaMarkerState.NO_MARKER,visible.markerState)
        assertEquals(1,port.deletes)
        assertEquals(NomediaMarkerState.PENDING_SHOW,store.writes[2].markerState)
    }
    @Test fun foreignNonemptyMarkerNeverModifiedOrRemoved()=runBlocking {
        val store=MemoryStore();val port=MarkerPort().apply { marker=NomediaMarkerProof("foreign",47) }
        val h=engine(store,port).hide("album",tree)
        assertEquals(NomediaMarkerState.FOREIGN_MARKER,h.markerState)
        assertNull(h.ownedDocumentId)
        assertEquals(0,port.creates)
        val restored=engine(store,port).show("album")
        assertEquals(NomediaMarkerState.FOREIGN_MARKER,restored.markerState)
        assertFalse(restored.mlHidden)
        assertEquals(0,port.deletes)
        assertEquals(47L,port.marker?.bytes)
    }
    @Test fun interruptedCreationNeverClaimsSuccessNorEnablesAutoReplay()=runBlocking {
        val store=MemoryStore()
        val port=MarkerPort().apply { failCreate=true }
        assertThrows(IllegalStateException::class.java) { runBlocking { engine(store,port).hide("album",tree) } }
        assertTrue(store.state.folders["album"]!!.excludedFromMl)
        assertEquals(NomediaMarkerState.FAILED,store.state.folders["album"]!!.markerState)
        assertThrows(IllegalStateException::class.java) { runBlocking { engine(store,port).show("album") } }
        assertEquals(0,port.deletes)
    }
    @Test fun revokedGrantBeforeWritingMakesNoRegistryOrMarkerChange()=runBlocking {
        val store=MemoryStore()
        val port=MarkerPort().apply { deny=true }
        assertThrows(IllegalStateException::class.java) { runBlocking { engine(store,port).hide("album",tree) } }
        assertTrue(store.writes.isEmpty())
        assertEquals(0,port.creates)
    }
    @Test fun changedOwnedMarkerRefusesDeletion()=runBlocking {
        val store=MemoryStore();val port=MarkerPort()
        engine(store,port).hide("album",tree)
        port.marker=NomediaMarkerProof("other",0)
        assertThrows(IllegalStateException::class.java) { runBlocking { engine(store,port).show("album") } }
        assertEquals(0,port.deletes)
        assertTrue(store.state.folders["album"]!!.excludedFromMl)
    }
    @Test fun samePhysicalTreeCannotBeOwnedByTwoHiddenCatalogFolders()=runBlocking {
        val store=MemoryStore()
        val port=MarkerPort()
        engine(store,port).hide("folder-first",tree)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { engine(store,port).hide("folder-second",tree) }
        }
        assertEquals(1,port.creates)
        assertTrue(store.state.folders["folder-first"]!!.excludedFromMl)
        assertFalse(store.state.folders.containsKey("folder-second"))
    }
    @Test fun reconcileCrashNeverDeletesOrRecreatesMarker()=runBlocking {
        val store=MemoryStore(NomediaFolderSnapshot(mapOf("folder" to
            NomediaFolderRecord("folder",tree,true,NomediaMarkerState.PENDING_HIDE))))
        val port=MarkerPort().apply { marker=NomediaMarkerProof("foreign-created-after-crash",0) }
        val record=engine(store,port).reconcile("folder")
        assertEquals(NomediaMarkerState.FOREIGN_MARKER,record.markerState)
        assertTrue(record.excludedFromMl)
        assertEquals(0,port.creates)
        assertEquals(0,port.deletes)
    }
    @Test fun unknownOrPendingRegistryNeverOpensFiles()=runBlocking {
        val store=MemoryStore(NomediaFolderSnapshot(unknown=true));val port=MarkerPort()
        assertThrows(IllegalStateException::class.java) { runBlocking { engine(store,port).hide("x",tree) } }
        assertEquals(0,port.creates)
        val pending=NomediaFolderRecord("x",tree,true,NomediaMarkerState.PENDING_HIDE)
        store.state=NomediaFolderSnapshot(mapOf("x" to pending))
        assertThrows(IllegalStateException::class.java) { runBlocking { engine(store,port).hide("x",tree) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { engine(store,port).show("x") } }
        assertEquals(0,port.deletes)
    }
    @Test fun oneEightFortySixMemberHistoriesNeverChangedByVisibility()=runBlocking {
        for(count in listOf(1,8,46)) {
            val store=MemoryStore();val port=MarkerPort()
            val history=(1..count).map { i -> "id-$i" to ("content://media/external/audio/$i" to (1_600_000_000_000L+i)) }.toMap()
            val before=history.toMap()
            engine(store,port).hide("group$count",tree)
            assertEquals(before,history)
            assertEquals(1,port.creates)
            engine(store,port).show("group$count")
            assertEquals(before,history)
            assertEquals(1,port.deletes)
        }
    }
    @Test fun foreignMarkerReplacedBeforeRestoreNeverDeleted()=runBlocking {
        val store=MemoryStore();val port=MarkerPort()
        engine(store,port).hide("x",tree)
        port.mutateBeforeDelete=true
        assertThrows(IllegalStateException::class.java) { runBlocking { engine(store,port).show("x") } }
        assertTrue(store.state.folders["x"]!!.excludedFromMl)
        assertEquals(NomediaMarkerState.FAILED,store.state.folders["x"]!!.markerState)
    }
}

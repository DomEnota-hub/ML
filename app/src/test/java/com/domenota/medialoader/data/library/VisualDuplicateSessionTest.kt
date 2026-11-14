package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.database.DownloadEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class VisualDuplicateSessionTest {
    private fun file(id:String)=VisualFingerprint(id,"content://media/$id",MediaType.PHOTO,100,10,"abc",100,100,null,listOf(123L),true)
    private val session=VisualDuplicateSession(VisualScanResult(listOf(VisualDuplicateGroup(listOf(file("a"),file("b"),file("c")),VisualMatch.EXACT)),2),setOf("a","b"),0,17,123)
    @Test fun roundTrip(){assertEquals(session,VisualDuplicateSessionStore.decode(VisualDuplicateSessionStore.encode(session)))}
    @Test fun neverSelectAll(){assertEquals(setOf("a","b"),session.copy(selected=setOf("a","b","c","unknown")).normalized().selected)}
    @Test fun partialTrashKeepsUnconfirmed(){val next=session.removeConfirmed(setOf("content://media/a"));assertEquals(setOf("b"),next.selected);assertEquals(listOf("b","c"),next.result.groups.single().files.map{it.id})}
    @Test fun singletonRemovedFromResults(){assertTrue(session.removeConfirmed(setOf("content://media/a","content://media/b")).result.groups.isEmpty())}
    private fun ref(id:String)=LibraryMediaRef(id,setOf(id),
        DownloadEntity(id,"device","$id.jpg",MediaType.PHOTO,DownloadState.COMPLETED,"content://media/$id",100L,100L),null)
    @Test fun catalogIdentityRejectsChangedOrPrivateRows(){
        val snapshot=file("a");val valid=ref("a")
        assertTrue(VisualDuplicateScanner.matchesCatalog(snapshot,valid))
        assertFalse(VisualDuplicateScanner.matchesCatalog(snapshot,valid.copy(key="other")))
        assertFalse(VisualDuplicateScanner.matchesCatalog(snapshot,valid.copy(item=valid.item.copy(savedUri="content://media/other"))))
        assertFalse(VisualDuplicateScanner.matchesCatalog(snapshot,valid.copy(item=valid.item.copy(sizeBytes=101L))))
        assertFalse(VisualDuplicateScanner.matchesCatalog(snapshot,valid.copy(item=valid.item.copy(isPrivate=true))))
        assertFalse(VisualDuplicateScanner.matchesCatalog(snapshot,valid.copy(item=valid.item.copy(hidden=true))))
        assertFalse(VisualDuplicateScanner.matchesCatalog(snapshot,valid.copy(item=valid.item.copy(state=DownloadState.RUNNING))))
        assertFalse(VisualDuplicateScanner.matchesCatalog(snapshot,valid.copy(item=valid.item.copy(mediaType=MediaType.VIDEO))))
    }
    @Test fun reconcileRemovesStaleAndAmbiguousIdentitiesWithoutSelectingKeeper(){
        val a=ref("a");val b=ref("b");val c=ref("c")
        val restored=session.reconcile(listOf(a,b,c))
        assertEquals(session,restored)
        val stale=session.reconcile(listOf(a,b,c.copy(item=c.item.copy(isPrivate=true))))
        assertEquals(listOf("a","b"),stale.result.groups.single().files.map{it.id})
        assertEquals(setOf("a"),stale.selected)
        assertTrue(session.reconcile(listOf(a,a,b)).result.groups.isEmpty())
    }
}

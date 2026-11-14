package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.model.MediaType
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
}

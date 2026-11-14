package com.domenota.medialoader.data.library

import androidx.room.Room
import com.domenota.medialoader.core.database.*
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.data.HistoryRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class MediaOrganizationRepositoryTest {
    private lateinit var db:HistoryDatabase
    private lateinit var store:MediaOrganizationRepository
    private lateinit var history:HistoryRepository
    @Before fun setup(){
        db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),HistoryDatabase::class.java).allowMainThreadQueries().build()
        store=MediaOrganizationRepository(db);history=HistoryRepository(db.downloadDao(),db.catalogDao(),db)
    }
    @After fun close(){db.close()}
    private fun row(id:String,state:DownloadState=DownloadState.COMPLETED,private:Boolean=false,parent:String?=null)=
        DownloadEntity(id,"instagram","$id.jpg",MediaType.PHOTO,state,"content://media/external/images/media/1",100L,100L,isPrivate=private,derivedFromId=parent)
    private fun ref(row:DownloadEntity)=LibraryMediaRef(row.id,setOf("file-stat:${row.id}"),row,row.id)
    @Test fun collectionsAreManyToManyAndDeletingOneNeverDeletesMedia()=runBlocking {
        val original=row("a");history.save(original)
        val a=store.create("One");val b=store.create("Two")
        store.addMedia(a,listOf(ref(original),ref(original)));store.addMedia(b,listOf(ref(original)))
        store.addLink(a,"https://example.com/post");store.addLink(a,"https://example.com/post")
        assertEquals(2,db.organizationDao().members(a).size);assertEquals(1,db.organizationDao().members(b).size)
        store.delete(a);assertTrue(db.organizationDao().members(a).isEmpty())
        assertEquals(original,history.byId("a"));assertEquals(1,db.organizationDao().members(b).size)
    }
    @Test fun mixedPickerSelectionIsAtomicAndIdempotent()=runBlocking {
        val original=row("selected");history.save(original)
        val id=store.create("Picker")
        assertTrue(runCatching{store.addSelection(id,listOf(ref(original)),listOf("file:///bad"))}.isFailure)
        assertTrue(db.organizationDao().members(id).isEmpty())
        repeat(2){store.addSelection(id,listOf(ref(original),ref(original)),listOf("https://example.com/one","https://example.com/one"))}
        assertEquals(2,db.organizationDao().members(id).size)
        assertEquals(original,history.byId(original.id))
    }
    @Test fun inboxOnlyRecordsRealCompletionAndNeverRequeuesReviewed()=runBlocking {
        history.save(row("old"))
        history.save(row("new",DownloadState.RUNNING))
        history.completeDownload(row("new"),1000)
        assertEquals(listOf("new"),store.review.first().map{it.downloadId})
        store.setReviewed(listOf("new"))
        history.completeDownload(row("new"),2000)
        history.saveDerived(row("edit",parent="new"),"Редактирование фото")
        assertTrue(store.review.first().isEmpty())
        assertEquals("Редактирование фото",store.operations("edit").first().single().operation)
        history.recordPublishedDownload(row("vk-direct"),3000)
        assertEquals(listOf("vk-direct"),store.review.first().map{it.downloadId})
        store.setReviewed(listOf("vk-direct"))
        history.recordPublishedDownload(row("vk-direct"),4000)
        assertTrue(store.review.first().isEmpty())
    }
    @Test fun newCollectionRollsBackIfInitialFileIsInvalid()=runBlocking {
        val bad=ref(row("missing"))
        assertTrue(runCatching{store.create("Invalid",listOf(bad))}.isFailure)
        assertTrue(db.organizationDao().observeCollections().first().isEmpty())
    }
    @Test fun privateAndAmbiguousMediaCannotBeResolved()=runBlocking {
        val a=row("a");val b=row("b");history.save(a);history.save(b)
        val c=store.create("Test");store.addMedia(c,listOf(ref(a)))
        val members=db.organizationDao().members(c)
        assertEquals("a",MediaOrganizationPolicy.resolve(members,listOf(ref(a))).values.single().key)
        assertTrue(MediaOrganizationPolicy.resolve(members,listOf(ref(a.copy(isPrivate=true)))).isEmpty())
        assertTrue(MediaOrganizationPolicy.resolve(members,listOf(ref(a),ref(b).copy(aliases=setOf("file-stat:a")))).isEmpty())
        assertEquals("a",MediaOrganizationPolicy.passport(ref(row("edit",parent="a")),listOf(ref(a))).parent?.key)
        assertTrue(runCatching{MediaOrganizationPolicy.link("file:///etc/passwd")}.isFailure)
    }
}

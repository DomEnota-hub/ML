package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class MediaPassportNoteStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val epoch get() = DuplicatePrivacyGuard.generation(context)
    private fun filename() = "passport-note-test-${UUID.randomUUID()}.json"
    private fun ref(id: String, app: String? = id, aliases: Set<String> = setOf("file-stat:$id"),
        type: MediaType = MediaType.PHOTO) = LibraryMediaRef(id,aliases,
        DownloadEntity(id,"device","$id.jpg",type,DownloadState.COMPLETED,"content://test/$id",100L,100L),app)

    @Test fun notesPersistEditAndDeleteWithoutChangingOriginalOrCopy() = runBlocking {
        val name=filename()
        val a=ref("original")
        val b=ref("copy").let { it.copy(item=it.item.copy(derivedFromId=a.key)) }
        val catalog=MediaPassportNoteCatalog(listOf(a,b))
        val store=MediaPassportNoteStore(context,name)
        val note="Привет\nЗаметка 🐾"
        assertEquals("",store.read(catalog,a,epoch))
        assertEquals(note,store.save(catalog,a,epoch,"",note))
        val reopened=MediaPassportNoteStore(context,name)
        assertEquals(note,reopened.read(catalog,a,epoch))
        assertEquals("",reopened.read(catalog,b,epoch))
        assertTrue(runCatching{reopened.save(catalog,a,epoch,"","stale")}.isFailure)
        assertEquals("Изменено",reopened.save(catalog,a,epoch,note,"Изменено"))
        assertEquals("",reopened.save(catalog,a,epoch,"Изменено","  "))
        assertEquals("",MediaPassportNoteStore(context,name).read(catalog,a,epoch))
        assertTrue(reopened.search(catalog,"Изменено",epoch).isEmpty())
    }

    @Test fun searchReturnsOnlyUnambiguousAdmittedVisualRows() = runBlocking {
        val a=ref("photo")
        val b=ref("video",type=MediaType.VIDEO)
        val c=ref("audio",type=MediaType.AUDIO)
        val store=MediaPassportNoteStore(context,filename())
        val all=MediaPassportNoteCatalog(listOf(a,b,c))
        for (row in listOf(a,b,c)) store.save(all,row,epoch,"","Поезд")
        assertEquals(setOf(a,b),store.search(all,"ПОЕЗД",epoch).toSet())
        assertEquals(listOf(b),store.search(MediaPassportNoteCatalog(listOf(b)),"поезд",epoch))
        val hidden=a.copy(item=a.item.copy(hidden=true))
        val private=b.copy(item=b.item.copy(isPrivate=true))
        val blocked=MediaPassportNoteCatalog(listOf(hidden,private,c))
        assertTrue(store.search(blocked,"поезд",epoch).isEmpty())
        assertTrue(runCatching{store.read(blocked,private,epoch)}.isFailure)
    }

    @Test fun appIdentitySurvivesRenameAndPhoneIdentityRejectsRecycledLocator() = runBlocking {
        val store=MediaPassportNoteStore(context,filename())
        val a=ref("a")
        val phone=ref("phone:visual:1",app=null,aliases=setOf("file-stat:old"))
        val catalog=MediaPassportNoteCatalog(listOf(a,phone))
        store.save(catalog,a,epoch,"","App")
        store.save(catalog,phone,epoch,"","Phone")
        val renamed=a.copy(item=a.item.copy(originalName="renamed.jpg",savedUri="content://new/a"))
        val moved=phone.copy(key="phone:visual:2",item=phone.item.copy(id="phone:visual:2",savedUri="content://new/phone"))
        val next=MediaPassportNoteCatalog(listOf(renamed,moved))
        assertEquals("App",store.read(next,renamed,epoch))
        assertEquals("Phone",store.read(next,moved,epoch))
        val recycled=phone.copy(aliases=setOf("file-stat:new"))
        assertEquals("",store.read(MediaPassportNoteCatalog(listOf(recycled)),recycled,epoch))
        val ambiguous=phone.copy(key="other",item=phone.item.copy(id="other"))
        val conflict=MediaPassportNoteCatalog(listOf(phone,ambiguous))
        assertTrue(store.search(conflict,"Phone",epoch).isEmpty())
        assertTrue(runCatching{store.read(conflict,phone,epoch)}.isFailure)
    }

    @Test fun staleEpochAndUnconfirmedRowsCannotReadOrWriteNotes() = runBlocking {
        val a=ref("a")
        val store=MediaPassportNoteStore(context,filename())
        val catalog=MediaPassportNoteCatalog(listOf(a))
        store.save(catalog,a,epoch,"","secret")
        val stale=epoch+1
        assertTrue(runCatching{store.read(catalog,a,stale)}.isFailure)
        assertTrue(runCatching{store.save(catalog,a,stale,"secret","overwrite")}.isFailure)
        assertTrue(runCatching{store.search(catalog,"secret",stale)}.isFailure)
        val unavailable=a.copy(item=a.item.copy(savedUri=null))
        assertTrue(runCatching{store.read(MediaPassportNoteCatalog(listOf(unavailable)),unavailable,epoch)}.isFailure)
        assertEquals("secret",store.read(catalog,a,epoch))
    }

    @Test fun corruptDocumentAndOversizedInputNeverOverwriteExistingNote() = runBlocking {
        val name=filename()
        val a=ref("a")
        val catalog=MediaPassportNoteCatalog(listOf(a))
        val store=MediaPassportNoteStore(context,name)
        store.save(catalog,a,epoch,"","keep")
        assertTrue(runCatching{store.save(catalog,a,epoch,"keep","a".repeat(4001))}.isFailure)
        assertEquals("keep",store.read(catalog,a,epoch))
        val file=File(context.noBackupFilesDir,name)
        file.writeText("invalid-json")
        assertTrue(runCatching{store.save(catalog,a,epoch,"","new")}.isFailure)
        assertEquals("invalid-json",file.readText())
    }
}

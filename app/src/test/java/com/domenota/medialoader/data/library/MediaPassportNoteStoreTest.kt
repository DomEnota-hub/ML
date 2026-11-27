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
        assertEquals("",store.readText(catalog,a,epoch))
        assertEquals(note,store.saveText(catalog,a,epoch,"",note))
        val reopened=MediaPassportNoteStore(context,name)
        assertEquals(note,reopened.readText(catalog,a,epoch))
        assertEquals("",reopened.readText(catalog,b,epoch))
        assertTrue(runCatching{reopened.saveText(catalog,a,epoch,"","stale")}.isFailure)
        assertEquals("Изменено",reopened.saveText(catalog,a,epoch,note,"Изменено"))
        assertEquals("",reopened.saveText(catalog,a,epoch,"Изменено","  "))
        assertEquals("",MediaPassportNoteStore(context,name).readText(catalog,a,epoch))
        assertTrue(reopened.search(catalog,"Изменено",epoch).isEmpty())
    }

    @Test fun searchReturnsOnlyUnambiguousAdmittedVisualRows() = runBlocking {
        val a=ref("photo")
        val b=ref("video",type=MediaType.VIDEO)
        val c=ref("audio",type=MediaType.AUDIO)
        val store=MediaPassportNoteStore(context,filename())
        val all=MediaPassportNoteCatalog(listOf(a,b,c))
        for (row in listOf(a,b,c)) store.saveText(all,row,epoch,"","Поезд")
        assertEquals(setOf(a,b),store.search(all,"ПОЕЗД",epoch).toSet())
        assertEquals(listOf(b),store.search(MediaPassportNoteCatalog(listOf(b)),"поезд",epoch))
        val hidden=a.copy(item=a.item.copy(hidden=true))
        val private=b.copy(item=b.item.copy(isPrivate=true))
        val blocked=MediaPassportNoteCatalog(listOf(hidden,private,c))
        assertTrue(store.search(blocked,"поезд",epoch).isEmpty())
        assertTrue(runCatching{store.readText(blocked,private,epoch)}.isFailure)
    }

    @Test fun appIdentitySurvivesRenameAndPhoneIdentityRejectsRecycledLocator() = runBlocking {
        val store=MediaPassportNoteStore(context,filename())
        val a=ref("a")
        val phone=ref("phone:visual:1",app=null,aliases=setOf("file-stat:old"))
        val catalog=MediaPassportNoteCatalog(listOf(a,phone))
        store.saveText(catalog,a,epoch,"","App")
        store.saveText(catalog,phone,epoch,"","Phone")
        val renamed=a.copy(item=a.item.copy(originalName="renamed.jpg",savedUri="content://new/a"))
        val moved=phone.copy(key="phone:visual:2",item=phone.item.copy(id="phone:visual:2",savedUri="content://new/phone"))
        val next=MediaPassportNoteCatalog(listOf(renamed,moved))
        assertEquals("App",store.readText(next,renamed,epoch))
        assertEquals("Phone",store.readText(next,moved,epoch))
        val recycled=phone.copy(aliases=setOf("file-stat:new"))
        assertEquals("",store.readText(MediaPassportNoteCatalog(listOf(recycled)),recycled,epoch))
        val ambiguous=phone.copy(key="other",item=phone.item.copy(id="other"))
        val conflict=MediaPassportNoteCatalog(listOf(phone,ambiguous))
        assertTrue(store.search(conflict,"Phone",epoch).isEmpty())
        assertTrue(runCatching{store.readText(conflict,phone,epoch)}.isFailure)
    }

    @Test fun staleEpochAndUnconfirmedRowsCannotReadOrWriteNotes() = runBlocking {
        val a=ref("a")
        val store=MediaPassportNoteStore(context,filename())
        val catalog=MediaPassportNoteCatalog(listOf(a))
        store.saveText(catalog,a,epoch,"","secret")
        val stale=epoch+1
        assertTrue(runCatching{store.readText(catalog,a,stale)}.isFailure)
        assertTrue(runCatching{store.saveText(catalog,a,stale,"secret","overwrite")}.isFailure)
        assertTrue(runCatching{store.search(catalog,"secret",stale)}.isFailure)
        val unavailable=a.copy(item=a.item.copy(savedUri=null))
        assertTrue(runCatching{store.readText(MediaPassportNoteCatalog(listOf(unavailable)),unavailable,epoch)}.isFailure)
        assertEquals("secret",store.readText(catalog,a,epoch))
    }

    @Test fun corruptDocumentAndOversizedInputNeverOverwriteExistingNote() = runBlocking {
        val name=filename()
        val a=ref("a")
        val catalog=MediaPassportNoteCatalog(listOf(a))
        val store=MediaPassportNoteStore(context,name)
        store.saveText(catalog,a,epoch,"","keep")
        assertTrue(runCatching{store.saveText(catalog,a,epoch,"keep","a".repeat(4001))}.isFailure)
        assertEquals("keep",store.readText(catalog,a,epoch))
        val file=File(context.noBackupFilesDir,name)
        file.writeText("invalid-json")
        assertTrue(runCatching{store.saveText(catalog,a,epoch,"","new")}.isFailure)
        assertEquals("invalid-json",file.readText())
    }
    @Test fun viewerAndSearchSharePublicNoteStorageWithoutOcr() = runBlocking {
        val a=ref("public")
        val b=ref("phone:visual:7",app=null,aliases=setOf("file-stat:phone7"),type=MediaType.VIDEO)
        val catalog=MediaPassportNoteCatalog(listOf(a,b))
        val store=MediaPassportNoteStore(context,filename())
        var active=true
        var available=listOf(a,b)
        val notes=com.domenota.medialoader.ui.screens.organization.MediaPassportNotes(
            Any(),Any(),store,catalog,epoch,{active},{ids->available.filter{it.key in ids}})
        notes.saveText(a,"","Поезд на станции")
        notes.saveText(b,"","Поезд")
        assertEquals(setOf(a.item.id,b.item.id),notes.search("ПОЕЗ").ids)
        assertFalse(notes.search("поезд").incomplete)
        available=listOf(a)
        assertEquals(setOf(a.item.id),notes.search("поезд").ids)
        assertTrue(notes.search("поезд").incomplete)
        notes.saveText(a,"Поезд на станции","")
        assertTrue(notes.search("поезд").ids.isEmpty())
        active=false
        assertTrue(runCatching{notes.search("поезд")}.isFailure)
    }

    private suspend fun MediaPassportNoteStore.readText(catalog:MediaPassportNoteCatalog,ref:LibraryMediaRef,epoch:Long)=
        readNotes(catalog,ref,epoch).singleOrNull()?.text.orEmpty()

    private suspend fun MediaPassportNoteStore.saveText(catalog:MediaPassportNoteCatalog,ref:LibraryMediaRef,epoch:Long,expected:String,value:String):String {
        val previous=readNotes(catalog,ref,epoch)
        check(previous.singleOrNull()?.text.orEmpty()==expected)
        val next=if(value.isBlank())emptyList() else listOf(previous.singleOrNull()?.copy(text=value) ?: MediaPassportNote.create(value))
        return saveNotes(catalog,ref,epoch,previous,next).singleOrNull()?.text.orEmpty()
    }

    private suspend fun com.domenota.medialoader.ui.screens.organization.MediaPassportNotes.saveText(ref:LibraryMediaRef,expected:String,value:String):String {
        val previous=readNotes(ref)
        check(previous.singleOrNull()?.text.orEmpty()==expected)
        val next=if(value.isBlank())emptyList() else listOf(previous.singleOrNull()?.copy(text=value) ?: MediaPassportNote.create(value))
        return saveNotes(ref,previous,next).singleOrNull()?.text.orEmpty()
    }

    @Test fun multipleNotesMigrateLegacyAndSearchEveryEntryOnlyOnce() = runBlocking {
        val name=filename()
        val a=ref("legacy")
        val b=ref("other")
        val catalog=MediaPassportNoteCatalog(listOf(a,b))
        val keys=MediaPassportNoteIdentity.keys(a)
        val old=org.json.JSONObject().put("version",1).put("notes",org.json.JSONArray().put(
            org.json.JSONObject().put("keys",org.json.JSONArray(keys.sorted())).put("note","Поезд старый")))
        File(context.noBackupFilesDir,name).writeText(old.toString())
        val store=MediaPassportNoteStore(context,name)
        val legacy=store.readNotes(catalog,a,epoch)
        assertEquals("Поезд старый",legacy.single().text)
        assertNull(legacy.single().createdAt)
        val new=MediaPassportNote.create("Поезд новый")
        val both=store.saveNotes(catalog,a,epoch,legacy,legacy+new)
        val reopened=MediaPassportNoteStore(context,name)
        assertEquals(both,reopened.readNotes(catalog,a,epoch))
        assertEquals(listOf(a),reopened.search(catalog,"ПОЕЗД",epoch))
        assertEquals(listOf(a),reopened.search(catalog,"новый",epoch))
        assertTrue(reopened.readNotes(catalog,b,epoch).isEmpty())
        val edited=both.map{if(it.id==new.id)it.copy(text="Станция") else it}
        assertEquals(edited,reopened.saveNotes(catalog,a,epoch,both,edited))
        assertTrue(runCatching{reopened.saveNotes(catalog,a,epoch,both,emptyList())}.isFailure)
        assertTrue(reopened.search(catalog,"новый",epoch).isEmpty())
        assertEquals(listOf(a),reopened.search(catalog,"станция",epoch))
        val remaining=reopened.saveNotes(catalog,a,epoch,edited,edited.filterNot{it.id==legacy.single().id})
        assertEquals(listOf(new.copy(text="Станция")),remaining)
        assertTrue(reopened.search(catalog,"старый",epoch).isEmpty())
        assertEquals(remaining,MediaPassportNoteStore(context,name).readNotes(catalog,a,epoch))
    }

    @Test fun notesRejectStaleListAndCreationTimeChanges() = runBlocking {
        val a=ref("notes")
        val catalog=MediaPassportNoteCatalog(listOf(a))
        val store=MediaPassportNoteStore(context,filename())
        val entry=MediaPassportNote.create("one")
        val initial=store.saveNotes(catalog,a,epoch,emptyList(),listOf(entry))
        val two=store.saveNotes(catalog,a,epoch,initial,initial+MediaPassportNote.create("two"))
        assertTrue(runCatching{store.saveNotes(catalog,a,epoch,initial,emptyList())}.isFailure)
        assertTrue(runCatching{store.saveNotes(catalog,a,epoch,two,two.map{it.copy(createdAt=1L)})}.isFailure)
        assertEquals(two,store.readNotes(catalog,a,epoch))
    }
}

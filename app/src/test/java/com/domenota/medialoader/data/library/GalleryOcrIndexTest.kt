package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
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
class GalleryOcrIndexTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val epoch get() = DuplicatePrivacyGuard.generation(context)
    private fun ref(id: String, type: MediaType = MediaType.PHOTO, stat: String = "file-stat:$id") =
        LibraryMediaRef(id,setOf(stat),DownloadEntity(id,"device","$id.jpg",type,DownloadState.COMPLETED,
            "content://test/$id",100L,100L),id)
    private fun catalog(vararg refs: LibraryMediaRef) = GalleryOcrCatalog(refs.toList(),emptyMap(),emptySet())
    private fun index() = GalleryOcrIndex(context,"ocr-test-" + UUID.randomUUID() + ".db")

    @Test fun unrelatedMusicChangesDoNotInvalidatePhotoInputs() {
        val a=ref("a"); val audio=ref("song",MediaType.AUDIO)
        val original=GalleryOcrPolicy.photoInputs(listOf(a,audio),mapOf("content://test/a" to "v1","content://test/song" to "v1"))
        assertEquals(original,GalleryOcrPolicy.photoInputs(listOf(a,audio.copy(item=audio.item.copy(title="changed"))),
            mapOf("content://test/a" to "v1","content://test/song" to "v2")))
        assertNotEquals(original,GalleryOcrPolicy.photoInputs(listOf(a,audio),mapOf("content://test/a" to "v2")))
    }
    @Test fun checkpointSurvivesReopeningAndEmptyRecognitionCounts() = runBlocking {
        val a = ref("a"); val b = ref("b")
        val catalog = catalog(a,b)
        val name = "ocr-test-" + UUID.randomUUID() + ".db"
        val store = GalleryOcrIndex(context,name)
        val candidates = catalog.candidates
        assertEquals(2,store.checkpoint(listOf(candidates[0] to "ПрИвЕт\nWORLD", candidates[1] to ""),epoch){true}.size)
        val reopened = GalleryOcrIndex(context,name)
        assertEquals(candidates.map{it.key}.toSet(),reopened.reconcile(catalog,epoch){true})
        assertEquals(listOf(a),reopened.search(catalog,"привет world",epoch){true}.map{it.ref})
        assertTrue(reopened.search(catalog,"нет",epoch){true}.isEmpty())
    }

    @Test fun changedVersionCannotMatchAndReconcileKeepsUnchangedRows() = runBlocking {
        val a=ref("a"); val b=ref("b")
        val initial=catalog(a,b); val store=index()
        store.checkpoint(initial.candidates.map{it to "Поезд"},epoch){true}
        val changed=catalog(ref("a",stat="file-stat:changed"),b)
        assertEquals(listOf(b),store.search(changed,"поезд",epoch){true}.map{it.ref})
        assertEquals(setOf(changed.candidates.single{it.ref==b}.key),store.reconcile(changed,epoch){true})
        assertTrue(store.search(catalog(a),"поезд",epoch){true}.isEmpty())
    }

    @Test fun onlyProvenPublicPhotosAndUnambiguousIdsEnterCatalog() {
        val a=ref("a")
        val hidden=ref("hidden").let{it.copy(item=it.item.copy(hidden=true))}
        val private=ref("private").let{it.copy(item=it.item.copy(isPrivate=true))}
        val audio=ref("audio",MediaType.AUDIO)
        val noIdentity=ref("unknown").copy(aliases=emptySet())
        val blocked=ref("blocked")
        val conflict=a.copy(item=a.item.copy(savedUri="content://test/other"))
        val catalog=GalleryOcrCatalog(listOf(a,conflict,hidden,private,audio,noIdentity,blocked),emptyMap(),setOf(blocked.key))
        assertTrue(catalog.candidates.isEmpty())
        assertEquals(3,catalog.unverified)
    }

    @Test fun staleGuardCannotReadOrWriteAndPruneRemovesDeniedRecord() = runBlocking {
        val a=ref("a"); val all=catalog(a); val store=index()
        store.checkpoint(all.candidates.map{it to "secret"},epoch){true}
        assertTrue(runCatching{store.search(all,"secret",epoch+1){true}}.isFailure)
        assertTrue(runCatching{store.checkpoint(all.candidates.map{it to "changed"},epoch){false}}.isFailure)
        val denied=GalleryOcrCatalog(listOf(a),emptyMap(),setOf(a.key))
        assertTrue(store.search(denied,"secret",epoch){true}.isEmpty())
        assertTrue(store.reconcile(denied,epoch){true}.isEmpty())
        assertTrue(store.search(all,"secret",epoch){true}.isEmpty())
    }

    @Test fun privateMovePurgeRemovesPersistentIndexBeforeReuse() = runBlocking {
        val a=ref("a"); val all=catalog(a); val store=GalleryOcrIndex(context)
        store.checkpoint(all.candidates.map{it to "private"},epoch){true}
        synchronized(DuplicatePrivacyGuard.lock) { GalleryOcrIndex.clearForPrivacy(context) }
        assertFalse(store.exists())
        assertTrue(store.search(all,"private",epoch){true}.isEmpty())
    }
}

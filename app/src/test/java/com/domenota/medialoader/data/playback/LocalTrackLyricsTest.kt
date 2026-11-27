package com.domenota.medialoader.data.playback

import android.net.Uri
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.DuplicatePrivacyGuard
import com.domenota.medialoader.data.library.LibraryMediaRef
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class LocalTrackLyricsTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private fun access(file:File,private:Boolean=false):AudioBookmarkAdmission {
        val uri=Uri.fromFile(file).toString()
        val id=file.name
        val item=DownloadEntity(id,"device","Track",MediaType.AUDIO,DownloadState.COMPLETED,uri,null,0).copy(isPrivate=private)
        return AudioBookmarkAdmission(LibraryMediaRef(id,PublicMediaIdentity.uriKeys(uri),item,id),DuplicatePrivacyGuard.generation(context))
    }
    @Test fun importedDocumentIsVersionScopedAndPrivacyRemovalIsTargeted() {
        val a=File.createTempFile("lyrics-import-a-",".mp3",context.cacheDir).apply{writeBytes(byteArrayOf(1,2,3))}
        val b=File.createTempFile("lyrics-import-b-",".mp3",context.cacheDir).apply{writeBytes(byteArrayOf(4,5,6))}
        val doc=Uri.parse("content://test.lyrics/document/1")
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStreamSupplier(doc) {
            java.io.ByteArrayInputStream("[00:01]Test phrase".toByteArray())
        }
        try {
            val store=LocalTrackLyrics(context)
            val first=access(a)
            val second=access(b)
            assertEquals(LyricsOrigin.IMPORTED,store.importDocument(first,doc){true}?.origin)
            assertEquals(LyricsOrigin.IMPORTED,store.importDocument(second,doc){true}?.origin)
            assertEquals("Test phrase",LocalTrackLyrics(context).read(first){true}?.plain)
            assertNull(store.importDocument(first,doc){false})
            synchronized(DuplicatePrivacyGuard.lock) {
                LocalTrackLyrics.clearForPrivacy(context,first.ref.key,first.ref.aliases)
            }
            assertNull(store.read(first){true})
            assertEquals("Test phrase",store.read(second){true}?.plain)
            b.appendBytes(byteArrayOf(7))
            assertNull(store.read(second){true})
        } finally {
            synchronized(DuplicatePrivacyGuard.lock) {
                val first=access(a);val second=access(b)
                LocalTrackLyrics.clearForPrivacy(context,first.ref.key,first.ref.aliases)
                LocalTrackLyrics.clearForPrivacy(context,second.ref.key,second.ref.aliases)
            }
            a.delete();b.delete()
        }
    }
    @Test fun localSidecarRequiresLivePublicSourceAndNeverWritesAudio() {
        val audio=File.createTempFile("lyrics-test-",".mp3",context.cacheDir).apply{writeBytes(byteArrayOf(1,2,3))}
        val lrc=File(audio.parentFile,audio.nameWithoutExtension+".lrc").apply{writeText("[00:01.25]Test phrase")}
        try {
            val store=LocalTrackLyrics(context)
            val admission=access(audio)
            val result=store.read(admission){true}
            assertEquals(LyricsOrigin.SIDECAR,result?.origin)
            assertEquals(1250L,result?.timed?.single()?.atMs)
            assertNull(store.read(admission){false})
            assertNull(store.read(access(audio,true)){true})
            assertArrayEquals(byteArrayOf(1,2,3),audio.readBytes())
            audio.delete()
            assertNull(store.read(admission){true})
        } finally {audio.delete();lrc.delete()}
    }
}

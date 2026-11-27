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
    @Test fun downloadedCacheRequiresSameVersionAndNeverOverwritesImport() {
        val audio=File.createTempFile("lyrics-net-",".mp3",context.cacheDir).apply{writeBytes(byteArrayOf(1,2,3))}
        val doc=Uri.parse("content://test.lyrics/document/network")
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStreamSupplier(doc) {
            java.io.ByteArrayInputStream("Imported fixture".toByteArray())
        }
        try {
            val store=LocalTrackLyrics(context)
            val admission=access(audio)
            val uri=Uri.fromFile(audio)
            val v=com.domenota.medialoader.ui.components.readWaveformSourceVersion(context,uri)
            assertTrue(v.proven)
            val key=java.security.MessageDigest.getInstance("SHA-256")
                .digest(listOf(uri.toString(),v.size.toString(),v.modified.toString()).joinToString("|").toByteArray(Charsets.UTF_8))
                .joinToString(""){"%02x".format(it.toInt() and 255)}
            val target=NetworkLyricsTarget(key,LyricsTrackSignature("Test","Artist","Album",120000))
            val value=TrackLyrics("Network fixture",emptyList(),LyricsOrigin.LRCLIB)
            assertNull(store.saveNetwork(admission,target,value){false})
            assertEquals(value,store.saveNetwork(admission,target,value){true})
            assertEquals(value,LocalTrackLyrics(context).read(admission){true})
            assertEquals(LyricsOrigin.IMPORTED,store.importDocument(admission,doc){true}?.origin)
            assertNull(store.saveNetwork(admission,target,value){true})
            assertEquals("Imported fixture",store.read(admission){true}?.plain)
            audio.appendBytes(byteArrayOf(4))
            assertNull(store.saveNetwork(admission,target,value){true})
            assertNull(store.read(admission){true})
        } finally {
            synchronized(DuplicatePrivacyGuard.lock) {
                val a=access(audio)
                LocalTrackLyrics.clearForPrivacy(context,a.ref.key,a.ref.aliases)
            }
            audio.delete()
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

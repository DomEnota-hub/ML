package com.domenota.medialoader.data.catalog.visibility

import android.provider.DocumentsContract
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** Never write to public device storage in tests: only an isolated temp root. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ManagedLocalNomediaPortTest {
    private fun fixture(block:(File,File,String)->Unit) {
        val root=Files.createTempDirectory("ml-archive-test-").toFile()
        val folder=File(root,"Pictures/Album").apply { mkdirs() }
        val tree=DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents","primary:Pictures/Album").toString()
        try { block(root,folder,tree) }
        finally { root.walkBottomUp().forEach { it.delete() } }
    }
    private fun port(root:File,expected:List<NomediaMediaSignature>?=null)=
        ManagedLocalNomediaPort(expected,{root},{true}) { extension ->
            when(extension) {
                "jpg","jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "mp3" -> "audio/mpeg"
                "mp4" -> "video/mp4"
                else -> null
            }
        }

    @Test fun exactPhysicalAlbumCreatesOnlyOwnedMarkerAndRestoresWithoutTouchingMedia()=fixture { root,folder,tree ->
        val first=File(folder,"a.jpg").apply { writeBytes(byteArrayOf(1,2,3));setLastModified(1_600_000_000_000) }
        val second=File(folder,"b.mp3").apply { writeBytes(byteArrayOf(4,5,6,7));setLastModified(1_600_000_005_000) }
        val third=File(folder,"c.mp4").apply { writeBytes(byteArrayOf(8,9,10));setLastModified(1_600_000_010_000) }
        val files=listOf(first,second,third)
        val originals=files.map { Triple(it.readBytes().toList(),it.length(),it.lastModified()) }
        val expected=listOf(NomediaMediaSignature("a.jpg",3),NomediaMediaSignature("b.mp3",4),NomediaMediaSignature("c.mp4",3))
        val direct=port(root,expected)
        runBlocking {
            assertNull(direct.inspect(tree))
            assertTrue(nomediaInventoryMatches(expected,direct.listMedia(tree).map {
                NomediaMediaSignature(it.displayName,it.byteCount ?: -1L)
            }))
            val proof=direct.createEmpty(tree)
            assertTrue(proof.documentId.startsWith("ml-archive-v1:"))
            assertTrue(proof.bytes>0)
            assertEquals(proof,direct.inspect(tree))
            assertTrue(direct.deleteVerified(tree,proof.documentId))
            assertNull(direct.inspect(tree))
        }
        assertEquals(originals,files.map { Triple(it.readBytes().toList(),it.length(),it.lastModified()) })
        assertEquals(listOf("a.jpg","b.mp3","c.mp4"),files.map { it.name })
    }

    @Test fun sixSelectedFrom806PhysicalFilesNeverCreatesDirectoryWideMarker()=fixture { root,folder,tree ->
        for(i in 1..806) File(folder,"track-$i.mp3").writeBytes(byteArrayOf(i.toByte()))
        val selected=(1..6).map { NomediaMediaSignature("track-$it.mp3",1L) }
        val direct=port(root,selected)
        val actual=direct.listMedia(tree).map { NomediaMediaSignature(it.displayName,it.byteCount ?: -1L) }
        assertEquals(806,actual.size)
        assertFalse(nomediaInventoryMatches(selected,actual))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { direct.createEmpty(tree) }
        }
        assertFalse(File(folder,".nomedia").exists())
        assertEquals(806,folder.listFiles()?.size)
    }

    @Test fun foreignMarkerCannotBeRemovedOrClaimed()=fixture { root,folder,tree ->
        val foreign=File(folder,".nomedia").apply { writeText("existing-marker") }
        val direct=port(root,listOf(NomediaMediaSignature("a.jpg",2)))
        val inspection=runBlocking { direct.inspect(tree) }
        assertEquals("foreign-local",inspection?.documentId)
        assertThrows(IllegalStateException::class.java) { runBlocking { direct.deleteVerified(tree,"foreign-local") } }
        assertEquals("existing-marker",foreign.readText())
    }

    @Test fun replacementBeforeRestoreBlocksDelete()=fixture { root,folder,tree ->
        File(folder,"photo.jpg").writeBytes(byteArrayOf(1,2))
        val direct=port(root,listOf(NomediaMediaSignature("photo.jpg",2)))
        val original=runBlocking { direct.createEmpty(tree) }
        val marker=File(folder,".nomedia")
        marker.writeText("another-origin")
        assertThrows(IllegalStateException::class.java) {
            runBlocking { direct.deleteVerified(tree,original.documentId) }
        }
        assertEquals("another-origin",marker.readText())
    }

    @Test fun symlinkDirectoryIsRejectedBeforeMarkerMutation()=fixture { root,folder,tree ->
        val other=File(root,"external").apply { mkdir() }
        val link=File(folder,"outside")
        val linked=runCatching { Files.createSymbolicLink(link.toPath(),other.toPath()) }.isSuccess
        if(linked) {
            assertThrows(IllegalStateException::class.java) { port(root).listMedia(tree) }
            assertFalse(File(folder,".nomedia").exists())
        }
    }

    @Test fun narrowerTreeMustNotBeConvertedFromBroadOrTraversalPath()=fixture { root,folder,tree ->
        val invalid=listOf("primary:Pictures","primary:Documents/MediaLoaderHidden",
            "primary:Pictures/../DCIM","primary:Android/data")
        for(value in invalid) {
            val uri=DocumentsContract.buildTreeDocumentUri(
                "com.android.externalstorage.documents",value).toString()
            assertThrows(IllegalStateException::class.java) { port(root).listMedia(uri) }
        }
    }

    @Test fun noAccessCannotReadOrModifyFilesystem()=fixture { root,folder,tree ->
        File(folder,"photo.jpg").writeBytes(byteArrayOf(7))
        val direct=ManagedLocalNomediaPort(listOf(NomediaMediaSignature("photo.jpg",1)),{root},{false})
        assertThrows(IllegalStateException::class.java) { direct.listMedia(tree) }
        assertFalse(File(folder,".nomedia").exists())
    }
    @Test fun hiddenSubdirectoryMustNotHideUnrelatedMediaEvenWithSameNameAndSize()=fixture { root,folder,tree ->
        File(folder,"photo.jpg").writeBytes(byteArrayOf(1,2))
        File(folder,"extra").mkdirs()
        File(folder,"extra/photo.jpg").writeBytes(byteArrayOf(3,4))
        val expected=listOf(NomediaMediaSignature("photo.jpg",2))
        assertThrows(IllegalStateException::class.java) { port(root,expected).listMedia(tree) }
        assertFalse(File(folder,".nomedia").exists())
    }

    @Test fun unknownSidecarIsNeverMistakenForOnlySelectedPhoto()=fixture { root,folder,tree ->
        File(folder,"photo.jpg").writeBytes(byteArrayOf(1,2))
        val sidecar=File(folder,"unknown.custom").apply{writeText("metadata")}
        val direct=port(root,listOf(NomediaMediaSignature("photo.jpg",2)))
        assertThrows(IllegalStateException::class.java) { direct.listMedia(tree) }
        assertThrows(IllegalStateException::class.java) { runBlocking { direct.createEmpty(tree) } }
        assertFalse(File(folder,".nomedia").exists())
        assertEquals("metadata",sidecar.readText())
        assertArrayEquals(byteArrayOf(1,2),File(folder,"photo.jpg").readBytes())
    }


    @Test fun missingMimeMappingRecoversOnlyStructurallyRecognizedSelectedMedia()=fixture { root,folder,tree ->
        // Small structural fixtures: not a claim of full decode/playability.
        val jpeg=File(folder,"a.JPG").apply {
            writeBytes(byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xc0.toByte(),
                0,11,8,0,1,0,1,1,1,0x11,0,0xff.toByte(),0xda.toByte(),
                0,8,1,1,0,0,63,0,1,0xff.toByte(),0xd9.toByte()))
        }
        fun box(type:String,body:ByteArray)=java.nio.ByteBuffer.allocate(8+body.size)
            .putInt(8+body.size).put(type.toByteArray(Charsets.US_ASCII)).put(body).array()
        val video=File(folder,"b.mp4").apply {
            writeBytes(box("ftyp","isom0000isom".toByteArray())+
                box("mdat",byteArrayOf(1,2,3))+box("moov",byteArrayOf(1,2,3)))
        }
        val files=listOf(jpeg,video)
        val before=files.map{it.readBytes().toList() to it.lastModified()}
        val expected=files.map{NomediaMediaSignature(it.name,it.length())}
        val direct=ManagedLocalNomediaPort(expected,{root},{true}) { null }
        assertEquals(setOf("image/jpeg","video/mp4"),direct.listMedia(tree).map{it.mimeType}.toSet())
        runBlocking {
            val proof=direct.createEmpty(tree)
            assertTrue(direct.deleteVerified(tree,proof.documentId))
        }
        assertEquals(before,files.map{it.readBytes().toList() to it.lastModified()})
        assertFalse(File(folder,".nomedia").exists())
        // A spoofed extension, conflicting MIME, unrecognized sidecar, or
        // an extra real file never authorizes a directory-wide marker.
        val fake=File(folder,"fake.jpg").apply{writeText("not a jpeg")}
        assertThrows(IllegalStateException::class.java){direct.listMedia(tree)}
        assertTrue(fake.delete())
        val conflict=ManagedLocalNomediaPort(expected,{root},{true}) { "text/plain" }
        assertThrows(IllegalStateException::class.java){conflict.listMedia(tree)}
        val extra=File(folder,"extra.jpg").apply{writeBytes(jpeg.readBytes())}
        assertThrows(IllegalStateException::class.java){runBlocking{direct.createEmpty(tree)}}
        assertFalse(File(folder,".nomedia").exists())
        assertTrue(extra.exists())
    }

}

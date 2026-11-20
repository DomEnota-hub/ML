package com.domenota.medialoader.data.catalog.visibility

import android.provider.DocumentsContract
import android.provider.MediaStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class NomediaAutoFolderPlanTest {
    @Test fun canonicalPrimaryDirectoryOpensExactSAFTree() {
        val target=nomediaSuggestedTree(MediaStore.VOLUME_EXTERNAL_PRIMARY,"Pictures/MLTest/")
        assertNotNull(target)
        assertEquals("primary:Pictures/MLTest",DocumentsContract.getTreeDocumentId(target))
    }

    @Test fun broadPathsTraversalsAndOtherVolumesDoNotSuggestUnsafeTree() {
        for(path in listOf("","/Pictures/ML","Pictures","Music",
            "DCIM","Downloads","Pictures/../DCIM","Documents/MediaLoaderHidden",
            "Pictures//SomeAlbum","Android/data","Pictures\\Danger","Pictures/X:evil")) {
            assertNull(path,nomediaSuggestedTree(MediaStore.VOLUME_EXTERNAL_PRIMARY,path))
        }
        assertNull(nomediaSuggestedTree("9A2B-1111","Music/Album/"))
        assertNull(nomediaSuggestedTree(null,"Pictures/MLTest/"))
        assertNull(nomediaSuggestedTree(MediaStore.VOLUME_EXTERNAL_PRIMARY,null))
    }

    @Test fun selectedAndPhysicalCatalogMustMatchExactlyFor1_8_46Media() {
        for(n in listOf(1,8,46)){
            val expected=(1..n).map{NomediaMediaSignature("test-${it}.jpg",1000L+it)}
            assertTrue(nomediaInventoryMatches(expected,expected.reversed()))
            assertFalse(nomediaInventoryMatches(expected,expected+NomediaMediaSignature("other.mp3",60)))
            assertFalse(nomediaInventoryMatches(expected,expected.dropLast(1)))
            assertFalse(nomediaInventoryMatches(expected,expected.dropLast(1)+NomediaMediaSignature("test-${n}.jpg",0)))
        }
    }

    @Test fun ambiguousAndUnknownFileIdentityNeverCountsAsVerified() {
        assertFalse(nomediaInventoryMatches(emptyList(),emptyList()))
        assertFalse(nomediaInventoryMatches(listOf(NomediaMediaSignature("a.mp3",1)),
            listOf(NomediaMediaSignature("a.mp3",-1))))
        val duplicate=List(2){NomediaMediaSignature("shared.jpg",45)}
        assertTrue(nomediaInventoryMatches(duplicate,duplicate))
        assertFalse(nomediaInventoryMatches(duplicate,listOf(NomediaMediaSignature("shared.jpg",45))))
    }
    @Test fun virtualSixFrom806NeverAuthorizesWholePhysicalFolderMarker() {
        val physical=(1..806).map { NomediaMediaSignature("track-$it.mp3",1000L+it) }
        val selected=physical.take(6)
        assertEquals(ArchiveInventoryVerdict.RequiresSelectiveArchive(6,806),
            archiveInventoryVerdict(selected,physical))
        assertEquals(ArchiveInventoryVerdict.ExactPhysicalFolder,
            archiveInventoryVerdict(physical,physical.reversed()))
        assertEquals(ArchiveInventoryVerdict.Unverified,
            archiveInventoryVerdict(selected,physical.drop(3)))
        assertEquals(ArchiveInventoryVerdict.Unverified,
            archiveInventoryVerdict(selected,physical.drop(6)))
        assertEquals(ArchiveInventoryVerdict.Unverified,
            archiveInventoryVerdict(selected,physical.mapIndexed { index,value ->
                if(index==0)value.copy(bytes=-1) else value
            }))
    }

}

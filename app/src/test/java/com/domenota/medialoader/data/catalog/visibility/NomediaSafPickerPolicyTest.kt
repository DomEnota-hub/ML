package com.domenota.medialoader.data.catalog.visibility

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class NomediaSafPickerPolicyTest {
    private val authority="com.android.externalstorage.documents"

    @Test fun initialUriIsARealDocumentHintNotUngrantableTree() {
        for(dir in listOf("Pictures/MLTest/","Music/TestAlbum/","DCIM/Disposable/")) {
            val expected=nomediaSuggestedTree(MediaStore.VOLUME_EXTERNAL_PRIMARY,dir)!!
            val initial=NomediaSafPickerPolicy.suggestedInitialDocument(expected)
            assertEquals(authority,initial.authority)
            assertEquals(DocumentsContract.getTreeDocumentId(expected),
                DocumentsContract.getDocumentId(initial))
            assertTrue(initial.toString().contains("/document/"))
            assertFalse(initial.toString().contains("/tree/"))
            assertNotEquals(expected,initial)
        }
    }

    @Test fun onlyExactSafTreeReturnedFromSystemCanBeAuthorized() {
        val wanted=DocumentsContract.buildTreeDocumentUri(authority,"primary:Pictures/MLTest")
        val exact=DocumentsContract.buildTreeDocumentUri(authority,"primary:Pictures/MLTest")
        val parent=DocumentsContract.buildTreeDocumentUri(authority,"primary:Pictures")
        val neighbor=DocumentsContract.buildTreeDocumentUri(authority,"primary:Pictures/Another")
        val child=DocumentsContract.buildTreeDocumentUri(authority,"primary:Pictures/MLTest/Sub")
        assertTrue(NomediaSafPickerPolicy.isExactlyRequestedTree(wanted,exact))
        listOf(parent,neighbor,child,
            DocumentsContract.buildTreeDocumentUri(authority,"primary:"),
            DocumentsContract.buildTreeDocumentUri("com.example.malicious","primary:Pictures/MLTest"),
            DocumentsContract.buildDocumentUri(authority,"primary:Pictures/MLTest"),
            Uri.parse("file:///storage/emulated/0/Pictures/MLTest")
        ).forEach {
            assertFalse("$it should be denied",NomediaSafPickerPolicy.isExactlyRequestedTree(wanted,it))
        }
    }

    @Test fun initialPickerHintsFailClosedForForbiddenOrForeignTrees() {
        listOf("primary:Pictures","primary:DCIM","primary:Android/data",
            "primary:Documents/MediaLoaderHidden","primary:Pictures/../DCIM")
            .forEach { id ->
                val tree=DocumentsContract.buildTreeDocumentUri(authority,id)
                assertThrows(IllegalArgumentException::class.java){
                    NomediaSafPickerPolicy.suggestedInitialDocument(tree)
                }
            }
        assertThrows(IllegalArgumentException::class.java){
            NomediaSafPickerPolicy.suggestedInitialDocument(
                DocumentsContract.buildTreeDocumentUri("com.example.cloud","primary:Pictures/Album"))
        }
    }
}

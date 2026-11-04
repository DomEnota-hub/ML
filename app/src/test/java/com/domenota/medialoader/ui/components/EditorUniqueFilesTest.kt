package com.domenota.medialoader.ui.components

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import org.junit.Assert.*
import org.junit.Test

class EditorUniqueFilesTest {
    private fun file(id: String, uri: String, type: MediaType) = DownloadEntity(id, "device", "same-name", type,
        DownloadState.COMPLETED, uri, 1024L, 0L)
    @Test fun aliasCopiesAreShownOnceForPhotosVideosAndMusic() {
        for (type in MediaType.entries) {
            val original=file("download", "content://downloads/1", type)
            val phone=original.copy(id="phone:1", savedUri="content://media/1")
            val other=file("other", "content://media/2", type)
            assertEquals(listOf(original,other), editorUniqueFiles(listOf(original,phone,other),
                mapOf("download" to "download", "phone:1" to "download")))
        }
    }
    @Test fun sameUriWithDifferentIdsIsOneFileButNamesAndSizesAreNotIdentities() {
        val a=file("a", "content://media/1", MediaType.PHOTO)
        val b=file("b", "content://media/2", MediaType.PHOTO)
        assertEquals(listOf(a,b), editorUniqueFiles(listOf(a,a.copy(id="alias"),b), emptyMap()))
    }
    @Test fun privateRowsRemainAvailableOnlyWhenIncludedByUnlockedCaller() {
        val private=file("secret", "content://private/1", MediaType.VIDEO).copy(isPrivate=true)
        assertEquals(listOf(private), editorUniqueFiles(listOf(private), emptyMap()))
        assertTrue(editorUniqueFiles(emptyList(), emptyMap()).isEmpty())
        assertTrue(editorUniqueFiles(listOf(private.copy(hidden=true)), emptyMap()).isEmpty())
    }
}

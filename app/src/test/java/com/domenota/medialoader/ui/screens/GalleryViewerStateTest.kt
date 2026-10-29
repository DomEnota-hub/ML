package com.domenota.medialoader.ui.screens

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.ui.screens.gallery.GalleryViewerState
import com.domenota.medialoader.data.library.LibraryMediaRef
import org.junit.Assert.*
import org.junit.Test

class GalleryViewerStateTest {
    @Test fun restoredPhoneAliasResolvesButPendingPrivateAliasIsBlockedImmediately() {
        val app = row("download", MediaType.PHOTO)
        val ref = LibraryMediaRef("download", setOf("download", "phone:photo:42", "physical:42"), app, "download")
        assertEquals(setOf("download", "phone:photo:42"), GalleryViewerState.publicCandidates(listOf(ref), emptySet()).map { it.id }.toSet())
        assertTrue(GalleryViewerState.publicCandidates(listOf(ref), setOf("physical:42")).isEmpty())
        assertTrue(GalleryViewerState.publicCandidates(listOf(ref), null).isEmpty())
    }
    private fun row(id: String, type: MediaType) = DownloadEntity(id, "device", id, type,
        DownloadState.COMPLETED, "content://media/$id", null, 0)

    @Test fun queueKeepsVisibleMixedOrderAndNeverCrossesIntoPrivateOrMissingMedia() {
        val rows = listOf(row("photo1", MediaType.PHOTO), row("video", MediaType.VIDEO), row("photo2", MediaType.PHOTO),
            row("private", MediaType.PHOTO).copy(isPrivate = true), row("audio", MediaType.AUDIO))
        val ids = listOf("photo2", "private", "video", "missing", "photo1", "audio", "video")
        assertEquals(listOf("photo2", "video", "photo1"), GalleryViewerState.queue(ids, rows).map { it.id })
    }

    @Test fun deletedCurrentItemChoosesNextThenPreviousWithoutWrapping() {
        val ids = listOf("first", "middle", "last")
        assertEquals("last", GalleryViewerState.neighbor("middle", ids, setOf("first", "last")))
        assertEquals("first", GalleryViewerState.neighbor("last", ids, setOf("first")))
        assertNull(GalleryViewerState.neighbor("first", ids, emptySet()))
    }

    @Test fun videoExistsOnlyOnSettledVisiblePageAndIsReleasedAsSoonAsSwipeStarts() {
        assertTrue(GalleryViewerState.canCreateVideo(1, 1, false))
        assertFalse(GalleryViewerState.canCreateVideo(1, 2, false))
        assertFalse(GalleryViewerState.canCreateVideo(1, 1, true))
    }
}

package com.domenota.medialoader.data.playback

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.storage.SavedFileStatus
import org.junit.Assert.*
import org.junit.Test

class PrivateViewerQueueTest {
    private fun row(id: String, private: Boolean, type: MediaType) =
        DownloadEntity(id, "instagram", "photo", type, DownloadState.COMPLETED,
            "content://media/$id", 10L, 1L).copy(isPrivate = private)

    @Test fun bothPrivatePhotoAndVideoOpenWhileVaultUnlocked() {
        val photo = row("secret-p", true, MediaType.PHOTO)
        val video = row("secret-v", true, MediaType.VIDEO)
        val public = row("ordinary", false, MediaType.PHOTO)
        val result = PrivateViewerQueue.resolve(
            listOf("ordinary", "secret-v", "secret-p"), "secret-p", true,
            listOf(public, video, photo), emptyMap())
        assertEquals(listOf("secret-p", "secret-v"), result.map { it.id })
        assertTrue(result.all { it.isPrivate })
        assertTrue(PrivateViewerQueue.resolve(
            listOf("secret-p"), "secret-p", false,
            listOf(photo), emptyMap()).isEmpty())
    }

    @Test fun validatedVaultQueueSurvivesTheActualGalleryViewerFilter() {
        val photo = row("secret-p", true, MediaType.PHOTO)
        val video = row("secret-v", true, MediaType.VIDEO)
        val public = row("ordinary", false, MediaType.PHOTO)
        val audio = row("secret-a", true, MediaType.AUDIO)
        val rows = listOf(public, photo, video, audio)
        val snapshot = rows.map { it.id }
        val authorized = PrivateViewerQueue.resolve(snapshot, photo.id, true, rows, emptyMap())
        val viewer = com.domenota.medialoader.ui.screens.gallery.GalleryViewerState
            .queue(authorized.map { it.id }, authorized, privateMode = true)
        assertEquals(listOf(photo.id, video.id), viewer.map { it.id })
        assertEquals(listOf(photo.id), com.domenota.medialoader.ui.screens.gallery.GalleryViewerState
            .queue(listOf(photo.id), listOf(photo), privateMode = true).map { it.id })
        assertEquals(listOf(public.id), com.domenota.medialoader.ui.screens.gallery.GalleryViewerState
            .queue(snapshot, rows).map { it.id })
        assertEquals(listOf(photo.id, video.id), com.domenota.medialoader.ui.screens.gallery.GalleryViewerState
            .queue(snapshot, rows, privateMode = true).map { it.id })
        val locked = PrivateViewerQueue.resolve(snapshot, photo.id, false, rows, emptyMap())
        assertTrue(com.domenota.medialoader.ui.screens.gallery.GalleryViewerState
            .queue(snapshot, locked, privateMode = true).isEmpty())
    }

    @Test fun missingOrPublicFileCannotBeShownByVaultRoute() {
        val photo = row("secret-p", true, MediaType.PHOTO)
        val public = row("ordinary", false, MediaType.PHOTO)
        assertTrue(PrivateViewerQueue.resolve(listOf("ordinary"), "ordinary", true,
            listOf(photo, public), emptyMap()).isEmpty())
        assertTrue(PrivateViewerQueue.resolve(listOf("secret-p"), "secret-p", true,
            listOf(photo), mapOf("secret-p" to SavedFileStatus.MISSING)).isEmpty())
    }

    @Test fun directSingleFilePrivateOpeningWorksWithoutGallerySnapshot() {
        val video = row("secret-v", true, MediaType.VIDEO)
        assertEquals(listOf("secret-v"), PrivateViewerQueue.resolve(
            emptyList(), video.id, true, listOf(video), emptyMap()).map { it.id })
    }
}

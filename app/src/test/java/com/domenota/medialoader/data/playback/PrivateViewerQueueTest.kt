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

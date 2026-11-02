package com.domenota.medialoader.data.library

import com.domenota.medialoader.ui.model.DownloadUiItem
import com.domenota.medialoader.ui.model.DownloadUiState
import com.domenota.medialoader.ui.model.MediaKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneMediaTrashTest {
    private fun row(id: String, uri: String?) = DownloadUiItem(
        id = id, title = "test", subtitle = "phone", sizeLabel = null,
        savedUri = uri, state = DownloadUiState.COMPLETED, kind = MediaKind.IMAGE
    )

    @Test fun onlySelectedMediaStoreTrashUrisCanBeRestoredOrDeleted() {
        val photo = row("phone-trash:image:42", "content://media/external/images/media/42")
        val video = row("phone-trash:video:7", "content://media/external/video/media/7")
        val app = row("ml-7", "content://media/external/images/media/42")
        val malicious = row("phone-trash:image:8", "content://untrusted/external/images/media/8")
        val result = PhoneMediaTrash.authorizedUris(
            setOf(photo.id, video.id, app.id, malicious.id),
            listOf(photo, video, app, malicious)
        )
        assertEquals(2, result.size)
        assertEquals(photo.savedUri, result[0].toString())
        assertEquals(video.savedUri, result[1].toString())
        assertFalse(PhoneMediaTrash.isPhoneTrashId(app.id))
    }

    @Test fun cannotRestoreUrisNotInCurrentTrashSnapshot() {
        assertTrue(PhoneMediaTrash.authorizedUris(
            setOf("phone-trash:image:9000"), listOf(row("phone-trash:image:7",
                "content://media/external/images/media/7"))).isEmpty())
    }
}

package com.domenota.medialoader.ui.model

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class RecognitionArtworkPriorityTest {
    private fun row(type: MediaType) = DownloadEntity(
        id = "song", providerId = "instagram", originalName = "song.mp3",
        mediaType = type, state = DownloadState.COMPLETED,
        savedUri = "content://downloads/123", sizeBytes = 10L,
        createdAtEpochMillis = 1L,
        previewUrl = "https://example.com/publication.jpg",
        recognitionSource = "SHAZAM_DIRECT",
        recognitionArtworkUrl = "https://example.com/confirmed.jpg",
    )

    @Test fun confirmedAudioPrefersRecognitionArtworkWhenEmbeddedPictureIsMissing() {
        assertEquals("https://example.com/confirmed.jpg", row(MediaType.AUDIO).toUi().previewUrl)
    }

    @Test fun nonAudioKeepsPublicationPreview() {
        assertEquals("https://example.com/publication.jpg", row(MediaType.VIDEO).toUi().previewUrl)
    }
}

package com.domenota.medialoader.ui.screens.music

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import org.junit.Assert.*
import org.junit.Test

class HiddenMusicPreviewTest {
    private fun track(id: String = "phone", provider: String = "device") = DownloadEntity(
        id, provider, "$id.mp3", MediaType.AUDIO, DownloadState.COMPLETED,
        "content://media/external/audio/media/42", 100L, 0L,
    )

    @Test fun phoneAndMlDownloadsPreviewOnlyTheChosenHiddenTrack() {
        for (row in listOf(track(), track("ml", "instagram"))) {
            val hidden = MusicTrackVisibility.update(emptySet(), row, true)
            assertEquals(listOf(row.id), HiddenMusicPreview.queue(row, hidden))
            assertTrue("Preview must not unhide a track", MusicTrackVisibility.hidden(row, hidden))
            assertTrue(HiddenMusicPreview.queue(row, emptySet()).isEmpty())
        }
    }

    @Test fun rescannedIdentityCanPreviewWithoutModifyingHiddenAlias() {
        val original = track()
        val hidden = MusicTrackVisibility.update(emptySet(), original, true)
        val rescanned = original.copy(id = "new-id")
        assertEquals(listOf("new-id"), HiddenMusicPreview.queue(rescanned, hidden))
        assertTrue(MusicTrackVisibility.hidden(original, hidden))
    }

    @Test fun privateMissingFailedAndNonAudioCannotBePreviewed() {
        val row = track()
        val hidden = MusicTrackVisibility.update(emptySet(), row, true)
        val invalid = listOf(
            row.copy(isPrivate = true),
            row.copy(hidden = true),
            row.copy(savedUri = null),
            row.copy(state = DownloadState.FAILED),
            row.copy(mediaType = MediaType.VIDEO),
        )
        invalid.forEach { assertTrue(HiddenMusicPreview.queue(it, hidden).isEmpty()) }
    }
}

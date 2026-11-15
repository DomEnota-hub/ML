package com.domenota.medialoader.ui.model

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.ui.screens.organization.missingCollectionDisplayName
import org.junit.Assert.*
import org.junit.Test

class FileNameDisplayTest {
    private fun row(name: String, type: MediaType = MediaType.AUDIO, id: String = "id") =
        DownloadEntity(id, "device", name, type, DownloadState.COMPLETED,
            "content://media/external/audio/media/42", 123L, 42L)

    @Test fun finalKnownExtensionOnlyAndCaseInsensitive() {
        val examples = listOf(
            Triple("live.2026.MP3", MediaKind.AUDIO, "live.2026"),
            Triple("Иллюзия.mp4", MediaKind.VIDEO, "Иллюзия"),
            Triple("photo.edit.JPEG", MediaKind.IMAGE, "photo.edit"),
            Triple("photo.heic", MediaKind.IMAGE, "photo"),
            Triple("song.mp4", MediaKind.AUDIO, "song"),
            Triple("song.flac", MediaKind.AUDIO, "song"),
            Triple("v.webm", MediaKind.VIDEO, "v"),
        )
        for ((name, kind, expected) in examples) {
            assertEquals(name, FileNameDisplay.name(name, kind, false))
            assertEquals(expected, FileNameDisplay.name(name, kind, true))
        }
    }
    @Test fun ambiguousUnknownAndNonFileNamesAreUnchanged() {
        val names = listOf("T.N.T.", "title.v2", "track.unknown", "no extension", ".mp3", "..mp3", "song.jpg", "https://a.test/song.mp3", "folder/song.mp3", "folder\\song.mp3", "")
        for (name in names) assertEquals(name, FileNameDisplay.name(name, MediaKind.AUDIO, true))
    }
    @Test fun tagTitleIsNeverTreatedAsFilenameEvenWithKnownSuffix() {
        val original = row("file.mp3")
        for (tag in listOf("AC/DC — T.N.T.", "Song.mp3", "live.2026")) {
            assertEquals(tag, FileNameDisplay.audioTitle(original.copy(sourceAudioTitle = tag), true))
            assertEquals(tag, FileNameDisplay.audioTitle(original.copy(recognizedTitle = tag, sourceAudioTitle = "Different"), true))
        }
        assertEquals("file", FileNameDisplay.audioTitle(original, true))
        assertEquals("file.mp3", FileNameDisplay.audioTitle(original, false))
    }
    @Test fun collidingDisplayNamesDoNotChangeAnyIdentityOrOriginalName() {
        val mp3 = row("same.mp3", id = "a")
        val flac = row("same.flac", id = "b")
        assertEquals(FileNameDisplay.name(mp3, true), FileNameDisplay.name(flac, true))
        assertNotEquals(mp3.id, flac.id)
        assertEquals("same.mp3", mp3.originalName)
        assertEquals("same.flac", flac.originalName)
        assertEquals("same.mp3", mp3.toUi().title)
        assertEquals("same.flac", flac.toUi().title)
        assertEquals("same.mp3", FileNameDisplay.name(mp3, false))
    }
    @Test fun missingSheetAlwaysUsesSameConservativeFormatter() {
        val photo = row("old.photo.JPG", MediaType.PHOTO)
        assertEquals("old.photo", missingCollectionDisplayName(photo))
        assertEquals("old.photo.JPG", FileNameDisplay.name(photo, false))
        assertEquals("legacy.suffix", missingCollectionDisplayName(photo.copy(originalName = "legacy.suffix")))
    }
}

package com.domenota.medialoader.data.playback

import androidx.media3.common.MediaItem
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioPlaybackMetadataTest {
    private fun row(id: String = "phone:audio:42", private: Boolean = false) = DownloadEntity(id, "device", "song.mp3",
        MediaType.AUDIO, DownloadState.COMPLETED, "content://media/external/audio/media/42", 123L, 0L,
        sourceAudioTitle = "Song", sourceAudioArtist = "Artist", isPrivate = private)

    @Test fun serviceSnapshotRestoresOpenableTrackWithoutAPhoneCatalog() {
        val original = AudioPlaybackMetadata.item(row(), setOf("file-stat:identity"))
        val transferred = MediaItem.fromBundle(original.toBundle())
        assertNull(transferred.localConfiguration)
        val restored = AudioPlaybackMetadata.publicSessionRows(listOf(transferred), emptyList(), emptySet()).single()
        assertEquals("phone:audio:42", restored.id)
        assertEquals(row().savedUri, restored.savedUri)
        assertEquals("Song", restored.sourceAudioTitle)
        assertEquals("Artist", restored.sourceAudioArtist)
    }
    @Test fun sessionAliasRemainsResolvableWhenHistoryUsesAnotherId() {
        val media = AudioPlaybackMetadata.item(row(), emptySet())
        val history = row("download-id").copy(originalName = "custom-name.mp3")
        val restored = AudioPlaybackMetadata.publicSessionRows(listOf(media), listOf(history), emptySet()).single()
        assertEquals("phone:audio:42", restored.id)
        assertEquals("custom-name.mp3", restored.originalName)
    }
    @Test fun privateBlockedAndUnaddressableSessionEntriesStayHidden() {
        val public = AudioPlaybackMetadata.item(row(), setOf("file-stat:identity"))
        val private = AudioPlaybackMetadata.item(row("secret", true), emptySet())
        val unavailable = MediaItem.Builder().setMediaId("missing-uri").build()
        assertTrue(AudioPlaybackMetadata.publicSessionRows(listOf(public, private, unavailable), emptyList(),
            setOf("file-stat:identity")).isEmpty())
    }
    @Test fun restoringLargeQueueReadsTheLibraryOnlyOnce() {
        val rows = (0 until 500).map { row("track-$it").copy(savedUri = "content://media/audio/$it") }
        var reads = 0
        val counted = object : AbstractList<DownloadEntity>() {
            override val size = rows.size
            override fun get(index: Int): DownloadEntity { reads++; return rows[index] }
        }
        val queue = rows.takeLast(100).map { AudioPlaybackMetadata.item(it, emptySet()) }
        assertEquals(100, AudioPlaybackMetadata.publicSessionRows(queue, counted, emptySet()).size)
        assertTrue("Do not rescan the library for each queue entry: $reads", reads <= rows.size)
    }
    @Test fun conflictingIdAndUriStillUseTheOriginalFirstMatchingRow() {
        val uriMatch = row("earlier").copy(originalName = "first")
        val idMatch = row("target").copy(savedUri = "content://other", originalName = "second")
        val media = AudioPlaybackMetadata.item(row("target"), emptySet())
        val resolved = AudioPlaybackMetadata.publicSessionRows(listOf(media),
            listOf(uriMatch, idMatch, uriMatch), emptySet()).single()
        assertEquals("first", resolved.originalName)
    }

}

package com.domenota.medialoader.data.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.DuplicatePrivacyGuard
import com.domenota.medialoader.data.library.LibraryMediaRef
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioBookmarkStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun ref(id: String, uri: String = "file:///a.mp3", app: String? = id,
        aliases: Set<String> = setOf("file-stat:1:2:3:4"), private: Boolean = false) =
        LibraryMediaRef(id, aliases, DownloadEntity(id, "device", "Track", MediaType.AUDIO,
            DownloadState.COMPLETED, uri, null, 0).copy(isPrivate = private), app)
    private fun access(ref: LibraryMediaRef) = AudioBookmarkAdmission(ref, DuplicatePrivacyGuard.generation(context))
    @Test fun namedPositionsPersistAndDeleteOnlyWithinTheirTrack() = runBlocking {
        val filename = "bookmark-test-${UUID.randomUUID()}.json"
        val first = AudioBookmarkStore(context, filename)
        val a = access(ref("a"))
        val b = access(ref("b", "file:///b.mp3", aliases = setOf("file-stat:2:3:4:5")))
        first.add(a, "Припев", 4200, 10_000)
        val before = first.add(a, "Начало", 100, 10_000)
        assertEquals(listOf(100L,4200L), before.map { it.positionMs })
        val reopened = AudioBookmarkStore(context, filename)
        assertEquals(before, reopened.list(a))
        assertTrue(reopened.list(b).isEmpty())
        assertTrue(reopened.delete(b, before.first().id).isEmpty())
        assertEquals(before, reopened.list(a))
        assertEquals(listOf("Припев"), reopened.delete(a, before.first().id).map { it.name })
    }
    @Test fun stableAppAndPhysicalAliasesSurviveDisplayNameAndIdChanges() {
        val first = ref("a")
        val renamed = first.copy(item = first.item.copy(originalName = "New title"))
        assertEquals(AudioBookmarkIdentity.keys(first), AudioBookmarkIdentity.keys(renamed))
        val phone = ref("phone:audio:9", app = null)
        assertTrue(AudioBookmarkIdentity.keys(first).intersect(AudioBookmarkIdentity.keys(phone)).isNotEmpty())
        assertTrue(AudioBookmarkIdentity.keys(ref("phone:audio:10", app = null, aliases = emptySet())).isEmpty())
        assertTrue(AudioBookmarkIdentity.keys(ref("secret", private = true)).isEmpty())
    }
    @Test fun resolutionRejectsPrivateAmbiguousOrChangedUri() {
        val a = ref("a")
        val media = AudioPlaybackMetadata.item(a.item, a.aliases)
        assertEquals(a, AudioBookmarkIdentity.resolve(media, mapOf("a" to a)))
        assertNull(AudioBookmarkIdentity.resolve(media, mapOf("a" to ref("a", "file:///replaced.mp3"))))
        assertNull(AudioBookmarkIdentity.resolve(media, mapOf("a" to ref("a", aliases = setOf("file-stat:9:8:7:6")))))
        assertNull(AudioBookmarkIdentity.resolve(media, mapOf("a" to a, "file-stat:1:2:3:4" to ref("b"))))
        val secret = media.buildUpon().setMediaMetadata(MediaMetadata.Builder()
            .setExtras(Bundle().apply { putBoolean("private_media", true) }).build()).build()
        assertNull(AudioBookmarkIdentity.resolve(secret, mapOf("a" to a)))
    }
    @Test fun unknownDurationAndOutOfRangePositionsAreRejected() {
        assertNull(AudioBookmarkIdentity.position(0, 0))
        assertNull(AudioBookmarkIdentity.position(-1, 1000))
        assertNull(AudioBookmarkIdentity.position(1000, 1000))
        assertEquals(999L, AudioBookmarkIdentity.position(999, 1000))
    }
    @Test fun privateOrUnidentifiedTrackCannotWriteOrRead() = runBlocking {
        val store = AudioBookmarkStore(context, "bookmark-test-${UUID.randomUUID()}.json")
        val denied = access(ref("secret", private = true))
        assertTrue(runCatching { store.add(denied, "Private", 1, 1000) }.isFailure)
        assertTrue(runCatching { store.list(denied) }.isFailure)
        val unidentified = access(ref("phone:audio:20", app = null, aliases = emptySet()))
        assertTrue(runCatching { store.add(unidentified, "Unknown", 1, 1000) }.isFailure)
    }
    @Test fun corruptStoreIsNotSilentlyOverwritten() = runBlocking {
        val filename = "bookmark-test-${UUID.randomUUID()}.json"
        val file = java.io.File(context.noBackupFilesDir, filename)
        file.writeText("invalid-json")
        val store = AudioBookmarkStore(context, filename)
        assertTrue(runCatching { store.add(access(ref("a")), "Name", 1, 1000) }.isFailure)
        assertEquals("invalid-json", file.readText())
    }
}

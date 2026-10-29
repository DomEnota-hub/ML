package com.domenota.medialoader.data.library

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistRepositoryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clear() {
        context.getSharedPreferences("media_playlists", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun membershipOrderingAndReloadRetainUnavailableIds() = runBlocking {
        val repository = PlaylistRepository(context)
        val id = repository.create("  Morning  ")
        repository.addTracks(id, listOf("app-a", "phone:audio:42", "app-a", "unavailable"))
        repository.moveTrack(id, "unavailable", Int.MIN_VALUE)
        assertEquals(listOf("unavailable", "app-a", "phone:audio:42"), repository.playlists.value.single().trackIds)
        repository.rename(id, "Evening")
        repository.removeTrack(id, "app-a")
        val restored = withTimeout(5_000) {
            PlaylistRepository(context).playlists.first { it.isNotEmpty() }.single()
        }
        assertEquals(MediaPlaylist(id, "Evening", listOf("unavailable", "phone:audio:42")), restored)
        repository.delete(id)
        assertTrue(repository.playlists.value.isEmpty())
        assertEquals("[]", context.getSharedPreferences("media_playlists", 0).getString("playlists", null))
    }

    @Test fun concurrentMutationsPreserveAllMemberships() = runBlocking {
        val repository = PlaylistRepository(context)
        val id = repository.create("Concurrent")
        coroutineScope { (1..30).map { index -> async { repository.addTracks(id, listOf("track-$index")) } }.forEach { it.await() } }
        assertEquals(30, repository.playlists.value.single().trackIds.distinct().size)
    }

    @Test fun invalidMutationDoesNotChangePersistedPlaylist() = runBlocking {
        val repository = PlaylistRepository(context)
        val id = repository.create("Safe")
        assertTrue(runCatching { repository.rename(id, "  ") }.isFailure)
        assertTrue(runCatching { repository.addTracks(id, listOf("")) }.isFailure)
        assertTrue(runCatching { repository.rename("missing", "Name") }.isFailure)
        assertEquals(MediaPlaylist(id, "Safe", emptyList()), repository.playlists.value.single())
    }

    @Test fun knownAliasesBecomeOneMemberWithoutDroppingUnavailableOrChangingOrder() = runBlocking {
        val repository = PlaylistRepository(context)
        val id = repository.create("Mixed")
        repository.addTracks(id, listOf("phone:audio:42", "missing", "download-42", "other"))
        repository.reconcileAliases(mapOf("phone:audio:42" to "download-42"))
        assertEquals(listOf("download-42", "missing", "other"), repository.playlists.value.single().trackIds)
        repository.addTracksResolved(id, listOf("phone:audio:42", "new"), mapOf("phone:audio:42" to "download-42"))
        assertEquals(listOf("download-42", "missing", "other", "new"), repository.playlists.value.single().trackIds)
        val restored = withTimeout(5000) { PlaylistRepository(context).playlists.first { it.isNotEmpty() }.single() }
        assertEquals(listOf("download-42", "missing", "other", "new"), restored.trackIds)
    }

    @Test fun historyOnlyCleanupKeepsMergedPhoneTrackPlayableAfterRestart() = runBlocking {
        val repository = PlaylistRepository(context)
        val id = repository.create("Keep files")
        repository.addTracks(id, listOf("before", "phone:audio:42", "after"))
        repository.reconcileAliases(mapOf("phone:audio:42" to "download-42", "download-42" to "download-42"))
        assertEquals(listOf("before", "download-42", "after"), repository.playlists.value.single().trackIds)
        // History is cleared; a fresh MediaStore scan still contains the same original track.
        val restarted = PlaylistRepository(context)
        restarted.awaitLoaded()
        restarted.reconcileAliases(mapOf("phone:audio:42" to "phone:audio:42"))
        assertEquals(listOf("before", "phone:audio:42", "after"), restarted.playlists.value.single().trackIds)
        restarted.addTracksResolved(id, listOf("phone:audio:42"), mapOf("phone:audio:42" to "phone:audio:42"))
        assertEquals(3, restarted.playlists.value.single().trackIds.size)
    }
    @Test fun readsLegacyPlaylistWithoutReferenceField() = runBlocking {
        context.getSharedPreferences("media_playlists", 0).edit().putString("playlists",
            """[{"id":"old","name":"Legacy","trackIds":["phone:audio:42","missing"]}]""").commit()
        val repository = PlaylistRepository(context)
        repository.awaitLoaded()
        assertEquals(listOf("phone:audio:42", "missing"), repository.playlists.value.single().trackIds)
        repository.reconcileAliases(mapOf("phone:audio:42" to "download"))
        assertEquals(listOf("download", "missing"), repository.playlists.value.single().trackIds)
    }
}

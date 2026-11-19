package com.domenota.medialoader.data.library

import android.content.Context
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
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
class ExternalMediaCatalogRepositoryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val track = LibraryMediaRef("phone:audio:42", setOf("phone:audio:42", "uri:content://media/external/audio/media/42"),
        DownloadEntity("phone:audio:42", "device", "Song.mp3", MediaType.AUDIO, DownloadState.COMPLETED,
            "content://media/external/audio/media/42", 1024, 12, sourceAudioTitle = "Song", sourceAudioArtist = "Artist"), null)

    @Before fun clear() { context.getSharedPreferences("external_media_catalog", 0).edit().clear().commit() }

    @Test fun identicalVisibilityDocumentsAreDecodedOnceAcross46FreshAccessChecks() = runBlocking {
        val first = ExternalMediaCatalogRepository(context)
        first.setFolder(track,"folder-one")
        val baseline = first.visibilitySnapshotRebuilds
        repeat(46) {
            val candidate = first.visibilityMembersByKey(track.key).single()
            assertEquals("folder-one",candidate.folderId)
            assertEquals(track.item.savedUri,candidate.ref.item.savedUri)
        }
        assertEquals(baseline+1,first.visibilitySnapshotRebuilds)
        assertEquals(setOf(track.key),first.albumMemberIds("folder-one"))
        assertEquals(baseline+1,first.visibilitySnapshotRebuilds)
        val other=ExternalMediaCatalogRepository(context)
        other.setFolder(track,"folder-two")
        assertEquals("folder-two",first.visibilityMembersByKey(track.key).single().folderId)
        assertEquals(baseline+2,first.visibilitySnapshotRebuilds)
        assertTrue(first.albumMemberIds("folder-one").isEmpty())
    }

    @Test fun corruptionAfterCachedReadFailsClosedAndRestoredSnapshotRemainsExact() = runBlocking {
        val repo = ExternalMediaCatalogRepository(context)
        repo.setFolder(track,"folder")
        assertEquals("folder",repo.visibilityMembersByKey(track.key).single().folderId)
        val prefs=context.getSharedPreferences("external_media_catalog",0)
        val saved=checkNotNull(prefs.getString("catalog",null))
        assertTrue(prefs.edit().putString("catalog","{not valid json").commit())
        assertTrue(runCatching {repo.visibilityMembersByKey(track.key)}.isFailure)
        assertTrue(prefs.edit().putString("catalog",saved).commit())
        assertEquals(saved,prefs.getString("catalog",null))
        assertEquals("folder",repo.visibilityMembersByKey(track.key).single().folderId)
    }

    @Test fun duplicateKeysRemainAmbiguousUnderIndexedLookup() = runBlocking {
        val repo=ExternalMediaCatalogRepository(context)
        repo.setFolder(track,"folder")
        val prefs=context.getSharedPreferences("external_media_catalog",0)
        val json=org.json.JSONObject(checkNotNull(prefs.getString("catalog",null)))
        val entries=json.getJSONArray("entries")
        entries.put(org.json.JSONObject(entries.getJSONObject(0).toString()).put("uri","content://media/external/audio/media/43"))
        assertTrue(prefs.edit().putString("catalog",json.toString()).commit())
        assertEquals(2,repo.visibilityMembersByKey(track.key).size)
        assertEquals(2,repo.visibilityEntries().size)
    }

    @Test fun favoriteAndFolderSurviveRestartWithoutImportingOrTouchingOriginal() = runBlocking {
        val repository = ExternalMediaCatalogRepository(context)
        repository.setFavorite(track, true)
        repository.setFolder(track, "folder-1")
        val restored = withTimeout(5000) { ExternalMediaCatalogRepository(context).entries.first { it.isNotEmpty() }.single() }
        assertEquals("phone:audio:42", restored.ref.key)
        assertNull(restored.ref.appDownloadId)
        assertEquals("content://media/external/audio/media/42", restored.ref.item.savedUri)
        assertEquals("Artist", restored.ref.item.sourceAudioArtist)
        assertEquals("folder-1", restored.folderId)
        assertTrue(restored.isFavorite)
        repository.setFolder(track, null)
        assertNull(repository.entries.value.single().folderId)
        assertTrue(repository.entries.value.single().isFavorite)
        repository.setFavorite(track, false)
        assertTrue(repository.entries.value.isEmpty())
    }

    @Test fun deletingFolderKeepsFavoriteAndDoesNotRemoveOtherFolderMembership() = runBlocking {
        val repository = ExternalMediaCatalogRepository(context)
        repository.setFavorite(track, true)
        repository.setFolder(track, "deleted")
        val second = track.copy(key = "phone:audio:99", aliases = setOf("phone:audio:99"),
            item = track.item.copy(id = "phone:audio:99", savedUri = "content://media/99"))
        repository.setFolder(second, "other")
        repository.removeFolder("deleted")
        assertNull(repository.entries.value.first { it.ref.key == track.key }.folderId)
        assertTrue(repository.entries.value.first { it.ref.key == track.key }.isFavorite)
        assertEquals("other", repository.entries.value.first { it.ref.key == second.key }.folderId)
    }

    @Test fun secondAddressUpdatesOneLogicalEntryAndRejectsPrivateMetadata() = runBlocking {
        val repository = ExternalMediaCatalogRepository(context)
        repository.setFavorite(track, true)
        val alias = track.copy(key = "document:42", aliases = track.aliases + "document:42")
        repository.setFolder(alias, "folder")
        assertEquals(1, repository.entries.value.size)
        assertEquals("phone:audio:42", repository.entries.value.single().ref.key)
        assertTrue(repository.entries.value.single().isFavorite)
        assertTrue(runCatching { repository.setFavorite(track.copy(item = track.item.copy(isPrivate = true)), true) }.isFailure)
    }

    @Test fun removalThroughDownloadAliasCannotResurrectOldExternalFavoriteOrFolder() = runBlocking {
        val repository = ExternalMediaCatalogRepository(context)
        repository.setFavorite(track, true)
        repository.setFolder(track, "folder")
        val downloadAlias = track.copy(key = "download", aliases = track.aliases + "download", appDownloadId = "download")
        repository.setExistingFavorite(downloadAlias, false)
        assertFalse(repository.entries.value.single().isFavorite)
        assertEquals("folder", repository.entries.value.single().folderId)
        repository.setExistingFolder(downloadAlias, null)
        assertTrue(repository.entries.value.isEmpty())
    }
}

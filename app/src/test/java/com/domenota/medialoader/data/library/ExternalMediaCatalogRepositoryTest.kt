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

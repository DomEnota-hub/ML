package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.core.database.CatalogAssignmentEntity
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.LibrarySource
import com.domenota.medialoader.data.library.MediaPlaylist
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryModeScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun galleryFavoritesAndSearchGiveViewerExactlyTheVisibleQueue() {
        val media = listOf(file("z", "Beach one.jpg"), file("b", "Beach two.jpg"), file("a", "Other.jpg"),
            file("private", "Beach private.jpg").copy(isPrivate = true), file("hidden", "Beach hidden.jpg").copy(hidden = true))
        var opened: Pair<String, List<String>>? = null
        compose.setContent { MaterialTheme {
            GalleryModeScreen(media, LibrarySource.APP, false, null, emptyList(),
                media.map { CatalogAssignmentEntity(it.id, null, true) }, {},
                { id, queue -> opened = id to queue }, {}, { _, _ -> }, { _, _ -> }, { "folder" }, {}, {}, { _, _ -> }, {})
        } }
        compose.onNodeWithText("Альбомы").performClick()
        compose.onNodeWithText("Избранное").performClick()
        compose.onNodeWithText("Поиск фото и видео").performTextInput("Beach")
        compose.onNodeWithContentDescription("Beach two.jpg").performClick()
        assertEquals("b" to listOf("z", "b"), opened)
        compose.onNodeWithContentDescription("Other.jpg").assertDoesNotExist()
        compose.onNodeWithContentDescription("Beach private.jpg").assertDoesNotExist()
    }

    @Test fun phoneGalleryOffersOnlySafeActionsAndAlbumQueue() {
        val media = listOf(file("a", "Camera.jpg").copy(groupId = "Camera"), file("b", "Screen.jpg").copy(groupId = "Screenshots"))
        var opened: Pair<String, List<String>>? = null
        var deleted: String? = null
        var favorite: Pair<String, Boolean>? = null
        compose.setContent { MaterialTheme {
            GalleryModeScreen(media, LibrarySource.PHONE, false, null, emptyList(), emptyList(), {},
                { id, queue -> opened = id to queue }, {}, { id, value -> favorite = id to value }, { _, _ -> }, { "folder" }, {}, { deleted = it }, { _, _ -> }, {})
        } }
        compose.onNodeWithText("Альбомы").performClick()
        compose.onNodeWithText("Camera").performClick()
        compose.onNodeWithContentDescription("Действия: Screen.jpg").assertDoesNotExist()
        compose.onNodeWithContentDescription("Действия: Camera.jpg").performClick()
        compose.onNodeWithText("Переименовать").assertDoesNotExist()
        compose.onNodeWithText("Переместить в личное").assertDoesNotExist()
        compose.onNodeWithText("В избранное").performClick()
        compose.waitForIdle()
        assertEquals("a" to true, favorite)
        compose.onNodeWithContentDescription("Действия: Camera.jpg").performClick()
        compose.onNodeWithText("Открыть").performClick()
        assertEquals("a" to listOf("a"), opened)
        compose.onNodeWithContentDescription("Действия: Camera.jpg").performClick()
        compose.onNodeWithText("Удалить").performClick()
        assertEquals("a", deleted)
    }

    @Test fun playlistUsesBothSourcesPreservesOrderAndReportsUnavailableMembership() {
        val a = file("app", "App.mp3", MediaType.AUDIO)
        val phone = file("phone", "Phone.mp3", MediaType.AUDIO)
        var opened: Pair<String, List<String>>? = null
        var removed: Pair<String, String>? = null
        var fileDeletes = 0
        compose.setContent { Music(listOf(a), listOf(a, phone), listOf(MediaPlaylist("mix", "Mix", listOf("phone", "missing", "app"))),
            onOpen = { id, queue -> opened = id to queue }, onRemove = { id, track -> removed = id to track },
            onDeleteFile = { fileDeletes++ }) }
        compose.onNodeWithText("Плейлисты").performClick()
        compose.onNodeWithText("Mix").performClick()
        compose.onNodeWithText("Недоступно треков: 1. Записи сохранены в плейлисте.").assertIsDisplayed()
        compose.onAllNodesWithText("Phone.mp3")[0].performClick()
        assertEquals("phone" to listOf("phone", "app"), opened)
        compose.onNodeWithContentDescription("Действия: Phone.mp3").performClick()
        compose.onNodeWithText("Убрать из плейлиста").performClick()
        assertEquals("mix" to "phone", removed)
        assertEquals(0, fileDeletes)
    }

    @Test fun playlistReorderAndDeleteRequireExplicitCallbacksAndConfirmation() {
        val tracks = listOf(file("a", "A.mp3", MediaType.AUDIO), file("b", "B.mp3", MediaType.AUDIO))
        var moved: Triple<String, String, Int>? = null
        var deletes = 0
        compose.setContent { Music(tracks, tracks, listOf(MediaPlaylist("p", "Road", listOf("a", "b"))),
            onMove = { id, track, delta -> moved = Triple(id, track, delta) }, onDelete = { deletes++ }) }
        compose.onNodeWithText("Плейлисты").performClick()
        compose.onNodeWithText("Road").performClick()
        compose.onNodeWithContentDescription("Действия: B.mp3").performClick()
        compose.onNodeWithText("Выше").performClick()
        assertEquals(Triple("p", "b", -1), moved)
        compose.onNodeWithContentDescription("Действия с плейлистом Road").performClick()
        compose.onNodeWithText("Удалить плейлист").performClick()
        assertEquals(0, deletes)
        compose.onNodeWithText("Аудиофайлы останутся на телефоне. Удалится только плейлист.").assertIsDisplayed()
        compose.onNodeWithText("Удалить плейлист").performClick()
        compose.waitForIdle()
        assertEquals(1, deletes)
    }

    @Test fun trackCanJoinPlaylistWithoutChangingOrDeletingTheFile() {
        val track = file("phone", "Phone.mp3", MediaType.AUDIO)
        var added: Pair<String, List<String>>? = null
        var deleted = 0
        compose.setContent { Music(listOf(track), listOf(track), listOf(MediaPlaylist("p", "Mix", emptyList())),
            onAdd = { id, ids -> added = id to ids }, onDeleteFile = { deleted++ }) }
        compose.onNodeWithContentDescription("Действия: Phone.mp3").performClick()
        compose.onNodeWithText("Добавить в плейлист").performClick()
        compose.onNodeWithText("Mix").performClick()
        compose.waitForIdle()
        assertEquals("p" to listOf("phone"), added)
        assertEquals(0, deleted)
    }

    @Test fun unavailableMemberCanBeRemovedWithoutShowingPrivateMetadataOrDeletingFiles() {
        val privateTrack = file("private", "Secret.mp3", MediaType.AUDIO).copy(isPrivate = true,
            sourceAudioArtist = "Secret artist", sourceAudioTitle = "Secret title")
        var removed: Pair<String, String>? = null
        var deleted = 0
        compose.setContent { Music(emptyList(), listOf(privateTrack), listOf(MediaPlaylist("p", "Mix", listOf("private"))),
            onRemove = { id, track -> removed = id to track }, onDeleteFile = { deleted++ }) }
        compose.onNodeWithText("Плейлисты").performClick()
        compose.onNodeWithText("Mix").performClick()
        compose.onNodeWithText("Недоступный трек").assertIsDisplayed()
        compose.onNodeWithText("Secret.mp3").assertDoesNotExist()
        compose.onNodeWithText("Secret artist").assertDoesNotExist()
        compose.onNodeWithText("Secret title").assertDoesNotExist()
        compose.onNodeWithContentDescription("Убрать недоступный трек 1 из плейлиста").performClick()
        compose.waitForIdle()
        assertEquals("p" to "private", removed)
        assertEquals(0, deleted)
    }

    @Test fun phoneMetadataTitleAndArtistAreSearchableAndDetermineVisibleQueue() {
        val title = file("title", "001.mp3", MediaType.AUDIO).copy(sourceAudioTitle = "Moonlight")
        val artist = file("artist", "002.mp3", MediaType.AUDIO).copy(sourceAudioArtist = "Aurora")
        var opened: Pair<String, List<String>>? = null
        compose.setContent { Music(listOf(title, artist), listOf(title, artist), emptyList(),
            onOpen = { id, ids -> opened = id to ids }) }
        compose.onNodeWithText("Поиск музыки").performTextInput("Moonlight")
        compose.onNodeWithContentDescription("Действия: 001.mp3").performClick()
        compose.onNodeWithText("Открыть").performClick()
        assertEquals("title" to listOf("title"), opened)
        compose.onNodeWithText("Поиск музыки").performTextReplacement("Aurora")
        compose.onNodeWithContentDescription("Действия: 002.mp3").performClick()
        compose.onNodeWithText("Открыть").performClick()
        assertEquals("artist" to listOf("artist"), opened)
    }

    @Test fun createAndRenamePlaylistPassEnteredNames() {
        var created: String? = null
        var renamed: Pair<String, String>? = null
        var entries by mutableStateOf(listOf(MediaPlaylist("p", "Old", emptyList())))
        compose.setContent { Music(emptyList(), emptyList(), entries,
            onCreate = { name ->
                created = name
                entries = entries + MediaPlaylist("new", name, emptyList())
                "new"
            }, onRename = { id, name ->
                renamed = id to name
                entries = entries.map { if (it.id == id) it.copy(name = name) else it }
            }) }
        compose.onNodeWithText("Плейлисты").performClick()
        compose.onNodeWithText("Создать плейлист").performClick()
        compose.onNodeWithText("Название").performTextInput("New")
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitForIdle()
        assertEquals("New", created)
        compose.onNodeWithContentDescription("Все плейлисты").performClick()
        compose.onNodeWithContentDescription("Действия с плейлистом Old").performClick()
        compose.onNodeWithText("Переименовать плейлист").performClick()
        compose.onNodeWithText("Название").performTextReplacement("Updated")
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitForIdle()
        assertEquals("p" to "Updated", renamed)
    }

    @Composable
    private fun Music(items: List<DownloadEntity>, all: List<DownloadEntity>, playlists: List<MediaPlaylist>,
        onOpen: (String, List<String>) -> Unit = { _, _ -> },
        onRemove: suspend (String, String) -> Unit = { _, _ -> },
        onMove: suspend (String, String, Int) -> Unit = { _, _, _ -> },
        onDelete: suspend (String) -> Unit = {}, onDeleteFile: (String) -> Unit = {},
        onAdd: suspend (String, List<String>) -> Unit = { _, _ -> },
        onCreate: suspend (String) -> String = { "new" }, onRename: suspend (String, String) -> Unit = { _, _ -> }) {
        MaterialTheme { MusicModeScreen(items, all, playlists, {}, false, null, onOpen, {}, onCreate,
            onRename, onDelete, onAdd, onRemove, onMove, {}, onDeleteFile) }
    }

    private fun file(id: String, name: String, type: MediaType = MediaType.PHOTO) = DownloadEntity(
        id, "device", name, type, DownloadState.COMPLETED, null, null, 0)
}

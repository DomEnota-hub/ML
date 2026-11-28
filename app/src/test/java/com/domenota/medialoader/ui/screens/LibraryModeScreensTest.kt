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
        compose.onNodeWithContentDescription("Альбомы").performClick()
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
        compose.onNodeWithContentDescription("Альбомы").performClick()
        compose.onNodeWithText("Камера").performClick()
        compose.onNodeWithContentDescription("Действия: Screen.jpg").assertDoesNotExist()
        compose.onNodeWithContentDescription("Действия: Camera.jpg").performClick()
        compose.onNodeWithText("Переименовать").assertDoesNotExist()
        compose.onNodeWithText("Переместить в личное").assertDoesNotExist()
        compose.onNodeWithText("В избранное").performClick()
        compose.waitForIdle()
        assertEquals("a" to true, favorite)
        compose.onNodeWithContentDescription("Camera.jpg").performClick()
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
        compose.onNodeWithContentDescription("Плейлисты").performClick()
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
        compose.onNodeWithContentDescription("Плейлисты").performClick()
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
        compose.onNodeWithContentDescription("Плейлисты").performClick()
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
        compose.onNodeWithTag("music-track-title").performClick()
        assertEquals("title" to listOf("title"), opened)
        compose.onNodeWithText("Поиск музыки").performTextReplacement("Aurora")
        compose.onNodeWithTag("music-track-artist").performClick()
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
        compose.onNodeWithContentDescription("Плейлисты").performClick()
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

    @Test fun galleryClearAndBackCloseSearchWithoutLeavingLibrary() {
        val media=listOf(file("a","Beach.jpg"),file("b","Other.jpg"))
        var visible by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            GalleryModeScreen(media,LibrarySource.APP,false,null,emptyList(),emptyList(),{},
                {_,_->},{},{_,_->},{_,_->},{"folder"},{},{},{_,_->},{},
                searchVisible=visible,onCloseSearch={visible=false})
        } }
        compose.onNodeWithText("Поиск фото и видео").performTextInput("Beach")
        compose.onNodeWithContentDescription("Other.jpg").assertDoesNotExist()
        compose.onNodeWithContentDescription("Очистить поиск").performClick()
        compose.onNodeWithText("Поиск фото и видео").assertExists()
        compose.onNodeWithContentDescription("Other.jpg").assertExists()
        compose.onNodeWithText("Поиск фото и видео").performTextInput("Beach")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Поиск фото и видео").assertDoesNotExist()
        compose.onNodeWithContentDescription("Other.jpg").assertExists()
        compose.runOnIdle { visible=true }
        compose.onNodeWithContentDescription("Очистить поиск").assertDoesNotExist()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Поиск фото и видео").assertDoesNotExist()
    }

    @Test fun musicClearAndBackCloseSearchWithoutLeavingLibrary() {
        val media=listOf(file("a","Moonlight.mp3",MediaType.AUDIO),file("b","Other.mp3",MediaType.AUDIO))
        var visible by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            MusicModeScreen(media,media,emptyList(),{},false,null,{_,_->},{},{"new"},{_,_->},{},{_,_->},{_,_->},{_,_,_->},{},{},
                searchVisible=visible,onCloseSearch={visible=false})
        } }
        compose.onNodeWithText("Поиск музыки").performTextInput("Moonlight")
        compose.onNodeWithTag("music-track-b").assertDoesNotExist()
        compose.onNodeWithContentDescription("Очистить поиск").performClick()
        compose.onNodeWithText("Поиск музыки").assertExists()
        compose.onNodeWithTag("music-track-b").assertExists()
        compose.onNodeWithText("Поиск музыки").performTextInput("Moonlight")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Поиск музыки").assertDoesNotExist()
        compose.onNodeWithTag("music-track-b").assertExists()
        compose.runOnIdle { visible=true }
        compose.onNodeWithContentDescription("Очистить поиск").assertDoesNotExist()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Поиск музыки").assertDoesNotExist()
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

    @Test fun musicScrollSurvivesPlayerReturnThroughColdProjectionAndExplicitTopStillWorks() {
        val media=(0 until 180).map { file("t"+it.toString().padStart(3,'0'), "Track "+it.toString().padStart(3,'0')+".mp3",MediaType.AUDIO) }
        var visible by mutableStateOf(true)
        var ready by mutableStateOf(true)
        var top by mutableStateOf(1)
        val catalog=MusicBrowseCatalog.build(media,media,emptyMap())
        compose.setContent {
            val holder=androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            MaterialTheme {
                if(visible)holder.SaveableStateProvider("music") {
                    MusicModeScreen(media,media,emptyList(),{},!ready,null,{_,_->visible=false},{},{"new"},
                        {_,_->},{},{_,_->},{_,_->},{_,_,_->},{},{},searchVisible=false,scrollToTopRequest=top,
                        preparedBrowse=com.domenota.medialoader.ui.components.BackgroundLibraryValue(
                            if(ready)catalog else MusicBrowseCatalog.EMPTY,ready))
                } else androidx.compose.material3.Text("Player")
            }
        }
        fun scrollValue()=compose.onNodeWithTag("music-library").fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        fun tracksReady()=compose.onAllNodesWithTag("music-track-t000").fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithTag("music-track-t088").fetchSemanticsNodes().isNotEmpty()
        compose.waitUntil(10000){tracksReady()}
        compose.onNodeWithTag("music-library").performScrollToIndex(90)
        val previous=scrollValue()
        org.junit.Assert.assertTrue(previous>80f)
        compose.onNodeWithTag("music-track-t088").performClick()
        compose.onNodeWithText("Player").assertExists()
        compose.runOnIdle{ready=false;visible=true}
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("music-track-t088").assertDoesNotExist()
        compose.runOnIdle{ready=true}
        compose.waitUntil(10000){tracksReady()}
        org.junit.Assert.assertEquals(previous,scrollValue(),0.5f)
        compose.onNodeWithTag("music-track-t088").assertIsDisplayed()
        compose.runOnIdle{top++}
        compose.waitUntil(10000){scrollValue()<1f}
        compose.onNodeWithTag("music-track-t000").assertIsDisplayed()
    }

    private fun file(id: String, name: String, type: MediaType = MediaType.PHOTO) = DownloadEntity(
        id, "device", name, type, DownloadState.COMPLETED, null, null, 0)
    @Test fun musicFolderViewToggleShowsNextActionAndListRowOpens() {
        var grid by mutableStateOf(true)
        var opened=false
        compose.setContent { MaterialTheme {
            androidx.compose.foundation.layout.Column {
                MusicFolderViewToggle(grid){grid=it}
                if(!grid)MusicFolderListRow("Папка","7 треков"){opened=true}
            }
        } }
        compose.onNodeWithContentDescription("Показать списком").performClick()
        compose.onNodeWithContentDescription("Показать сеткой").assertExists()
        compose.onNodeWithText("7 треков").assertExists()
        compose.onNodeWithText("Папка").performClick()
        compose.runOnIdle{org.junit.Assert.assertTrue(opened)}
        compose.onNodeWithContentDescription("Показать сеткой").performClick()
        compose.onNodeWithContentDescription("Показать списком").assertExists()
        compose.onNodeWithText("7 треков").assertDoesNotExist()
    }
}

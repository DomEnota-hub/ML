package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.domenota.medialoader.core.database.CatalogFolderEntity
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.LibrarySource
import com.domenota.medialoader.ui.screens.gallery.GalleryAlbumMovePolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GalleryBulkAlbumMoveTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>

    @Before fun openHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    }
    @After fun closeHost() { host.pause().stop().destroy() }

    @Test fun selectionMovesAllSelectedItemsIntoExistingAppFolder() {
        val moved = mutableListOf<Pair<String, String?>>()
        val rows = listOf("a", "b", "c").map {
            DownloadEntity(it, "device", "$it.jpg", MediaType.PHOTO,
                DownloadState.COMPLETED, "content://media/external/images/media/1", null, 1_000_000)
        }
        compose.runOnUiThread {
            host.get().setContent {
                MaterialTheme {
                    GalleryModeScreen(
                        items = rows, source = LibrarySource.APP, loading = false, message = null,
                        folders = listOf(CatalogFolderEntity("work", "Работа", "работа", 0L)),
                        assignments = emptyList(), header = {},
                        onOpen = { _, _ -> }, onOpenPrivate = {},
                        onFavorite = { _, _ -> }, onSetFolder = { id, folder -> moved += id to folder },
                        onCreateFolder = { "new" }, onShare = {}, onDelete = {},
                        onRename = { _, _ -> }, onMoveToPrivate = {},
                        searchVisible = false,
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("a.jpg").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("b.jpg").performClick()
        compose.onNodeWithContentDescription("Переместить выбранные в альбом").performClick()
        compose.onNodeWithText("Переместить в альбом").assertExists()
        compose.onNodeWithText("Работа").performClick()
        compose.onNodeWithText("Переместить 2").performClick()
        compose.waitForIdle()
        assertEquals(listOf("a" to "work", "b" to "work"), moved)
        compose.onNodeWithContentDescription("Переместить выбранные в альбом").assertDoesNotExist()
    }

    @Test fun completedItemsAreRemovedFromSelectionButFailuresRemain() {
        assertEquals(listOf("b", "c"), GalleryAlbumMovePolicy.remainingSelection(
            listOf("a", "b", "c"), setOf("a")))
        assertEquals("Перенесено: 1 из 3. Не удалось: 2. Необработанные файлы остались выделенными.",
            GalleryAlbumMovePolicy.summary(3, 1, 2))
    }
}

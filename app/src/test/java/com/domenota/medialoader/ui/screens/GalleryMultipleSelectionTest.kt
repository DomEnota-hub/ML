package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.LibrarySource
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GalleryMultipleSelectionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    @Before fun createHost() { host = Robolectric.buildActivity(ComponentActivity::class.java).setup() }
    @After fun destroyHost() { host.pause().stop().destroy() }
    @Test fun longPressStartsSelectionTapAddsAnotherAndDeleteDispatchesOneVisibleBatch() {
        val rows = listOf(row("a"), row("b"), row("c"), row("private").copy(isPrivate = true), row("hidden").copy(hidden = true))
        var opens = 0
        val deletions = mutableListOf<List<String>>()
        compose.runOnUiThread { host.get().setContent { MaterialTheme {
            GalleryModeScreen(rows, LibrarySource.PHONE, false, null, emptyList(), emptyList(), {},
                { _, _ -> opens++ }, {}, { _, _ -> }, { _, _ -> }, { "folder" }, {}, {}, { _, _ -> }, {},
                searchVisible = false, onDeleteSelected = { deletions += it })
        } } }
        compose.onNodeWithContentDescription("a.jpg").performTouchInput { longClick() }
        compose.onNodeWithText("Выбрано: 1").assertExists()
        compose.onNodeWithContentDescription("b.jpg").performClick()
        compose.onNodeWithText("Выбрано: 2").assertExists()
        compose.onNodeWithContentDescription("Удалить выбранное").performClick()
        assertEquals(listOf(listOf("a", "b")), deletions)
        assertEquals(0, opens)
        compose.onNodeWithContentDescription("Выбрать все").performClick()
        compose.onNodeWithText("Выбрано: 3").assertExists()
        compose.onNodeWithContentDescription("Отменить выбор").performClick()
        compose.onNodeWithContentDescription("Удалить выбранное").assertDoesNotExist()
    }
    private fun row(id: String) = DownloadEntity(id, "device", "$id.jpg", MediaType.PHOTO,
        DownloadState.COMPLETED, "content://media/external/images/media/$id", null, 1_000_000)
}

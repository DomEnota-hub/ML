package com.domenota.medialoader.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.data.library.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryHeaderActionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun compactSourceSelectionDoesNotReloadAlreadySelectedSource() {
        var changes = 0
        compose.setContent { MaterialTheme {
            var source by remember { mutableStateOf(LibrarySource.APP) }
            LibraryModeHeader(AppMode.GALLERY, source, {}, { source = it; changes++ }, {}, {})
        } }
        compose.onNodeWithContentDescription("Источник файлов").performClick()
        compose.onAllNodesWithText("Файлы приложения")[1].performClick()
        assertEquals(0, changes)
        compose.onNodeWithContentDescription("Источник файлов").performClick()
        compose.onNodeWithText("Все файлы телефона").performClick()
        assertEquals(1, changes)
        compose.onNodeWithText("Все файлы телефона").assertExists()
    }

    @Test fun topActionsReachSearchPrivateAndSettings() {
        var search = 0; var private = 0; var settings = 0
        compose.setContent { MaterialTheme {
            LibraryModeHeader(AppMode.MUSIC, LibrarySource.APP, {}, {}, {}, { settings++ },
                onSearch = { search++ }, onPrivate = { private++ })
        } }
        compose.onNodeWithContentDescription("Поиск").performClick()
        compose.onNodeWithContentDescription("Личное").performClick()
        compose.onNodeWithContentDescription("Дополнительно").performClick()
        compose.onNodeWithText("Настройки").performClick()
        assertEquals(1, search); assertEquals(1, private); assertEquals(1, settings)
    }
}

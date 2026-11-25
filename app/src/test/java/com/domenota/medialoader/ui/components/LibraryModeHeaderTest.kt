package com.domenota.medialoader.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.data.library.AppMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class LibraryModeHeaderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun titlesAreStaticInAllThreeModes() {
        var mode by mutableStateOf(AppMode.DOWNLOADER)
        compose.setContent { MaterialTheme { AppModeTitle(mode) } }
        for (item in AppMode.entries) {
            compose.runOnIdle { mode = item }
            compose.onNodeWithText(item.label).assertExists().assertHasNoClickAction()
            compose.onNodeWithContentDescription("Сменить режим").assertDoesNotExist()
            compose.onNodeWithTag("app-mode-chevron",useUnmergedTree=true).assertDoesNotExist()
            for (other in AppMode.entries.filter { it != item }) {
                compose.onNodeWithText(other.label).assertDoesNotExist()
            }
        }
    }

    @Test fun unifiedHeaderRemovesOnlyApprovedDuplicates(){
        var mode by mutableStateOf(AppMode.GALLERY)
        compose.setContent { MaterialTheme {
            LibraryModeHeader(mode,com.domenota.medialoader.data.library.LibrarySource.APP,
                {}, {},{},onCalendar={},
                onHiddenTracks={},unifiedDrawerRoutes=true)
        } }
        compose.onNodeWithContentDescription("Личное").assertDoesNotExist()
        compose.onNodeWithContentDescription("Архив").assertDoesNotExist()
        compose.onNodeWithContentDescription("Корзина").assertExists()
        compose.onNodeWithContentDescription("Поиск").assertExists()
        compose.onNodeWithContentDescription("Перейти к дате").assertExists()
        compose.runOnIdle{mode=AppMode.MUSIC}
        compose.onNodeWithContentDescription("Настройки").assertDoesNotExist()
        compose.onNodeWithContentDescription("Скрытые треки").assertExists()
        compose.onNodeWithContentDescription("Поиск").assertExists()
    }
}

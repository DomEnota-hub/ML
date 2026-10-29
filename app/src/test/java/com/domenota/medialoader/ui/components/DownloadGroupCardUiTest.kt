package com.domenota.medialoader.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.ui.model.DownloadUiItem
import com.domenota.medialoader.ui.model.DownloadUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class DownloadGroupCardUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val group = (1..3).map { DownloadUiItem("f$it", "file$it", "", null,
        state = DownloadUiState.COMPLETED, groupId = "post") }

    @Test fun collapsedMenuInvokesOnlyChosenCallbackAndDoesNotExpand() {
        var expanded = 0
        var favorite = 0
        var deleted = 0
        compose.setContent { MaterialTheme { DownloadGroupCard(group, false,
            { expanded++ }, {}, allFavorite = true, onFavorite = { favorite++ }, onDelete = { deleted++ }) } }
        compose.onNodeWithContentDescription("Действия с группой").performClick()
        compose.onNodeWithText("Убрать из избранного").performClick()
        assertEquals(1, favorite)
        assertEquals(0, expanded)
        assertEquals(0, deleted)
        compose.onNodeWithText("Убрать из избранного").assertDoesNotExist()
    }

    @Test fun summaryStillExpandsWhenGroupHasSeparateMenu() {
        var expanded = 0
        compose.setContent { MaterialTheme { DownloadGroupCard(group, false,
            { expanded++ }, {}, onDelete = {}) } }
        compose.onNodeWithText("Публикация · 3 файлов").performClick()
        assertEquals(1, expanded)
        compose.onNodeWithText("Сохранено 3 из 3").assertIsDisplayed()
    }
}

package com.domenota.medialoader.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.ui.model.DownloadUiItem
import com.domenota.medialoader.ui.model.DownloadUiState
import com.domenota.medialoader.data.archive.*
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.ui.screens.SimpleArchiveScreen
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

    @Test fun archiveActionRunsDirectlyWithoutOpeningGroupOrConfirmation() {
        var archived = 0
        var expanded = 0
        var deleted = 0
        compose.setContent { MaterialTheme { DownloadGroupCard(group, false,
            { expanded++ }, {}, onDelete = { deleted++ }, onArchive = { archived++ }) } }
        compose.onNodeWithContentDescription("Действия с группой").performClick()
        compose.onNodeWithText("В архив").performClick()
        assertEquals(1, archived)
        assertEquals(0, expanded)
        assertEquals(0, deleted)
        compose.onNodeWithText("В архив").assertDoesNotExist()
    }

    @Test fun privateGroupNeverOffersOrdinaryArchiveAction() {
        var archived = 0
        compose.setContent { MaterialTheme { DownloadGroupCard(group, false, {}, {},
            privateGroup = true, onRestore = {}, onArchive = { archived++ }) } }
        compose.onNodeWithContentDescription("Действия с группой").performClick()
        compose.onNodeWithText("В архив").assertDoesNotExist()
        assertEquals(0, archived)
    }
    @Test fun archiveCardsExpandAndSelectBothGroupKindsInListAndGrid() {
        val prefs = compose.activity.getSharedPreferences("ui", 0)
        prefs.edit().putBoolean("archive_grid", false).commit()
        val folder = SimpleArchiveGroup(SimpleArchiveGroupKind.VIRTUAL_FOLDER, "folder", "Тест")
        val post = SimpleArchiveGroup(SimpleArchiveGroupKind.PUBLICATION, "post", "Публикация")
        val records = listOf(
            SimpleArchiveRecord("one", "content://one", MediaType.PHOTO, "one.jpg",
                ArchiveEntrySource.DOWNLOADER, 100, 2, folder),
            SimpleArchiveRecord("two", "content://two", MediaType.PHOTO, "two.jpg",
                ArchiveEntrySource.DOWNLOADER, 100, 1, post)
        )
        var restored = emptySet<String>()
        compose.setContent { MaterialTheme {
            SimpleArchiveScreen(records, {}, {}, { restored = it })
        } }
        compose.onNodeWithText("Тест").assertIsDisplayed()
        compose.onNodeWithText("Публикация").assertIsDisplayed()
        compose.onNodeWithTag("archive-group-expand-VIRTUAL_FOLDER:folder").performClick()
        compose.onNodeWithText("one.jpg").assertIsDisplayed()
        compose.onNodeWithTag("archive-group-expand-VIRTUAL_FOLDER:folder").performClick()
        compose.onNodeWithTag("archive-group-select-VIRTUAL_FOLDER:folder").performClick()
        compose.onNodeWithTag("simple-archive-restore").performClick()
        assertEquals(setOf("one"), restored)
        compose.onNodeWithContentDescription("Отменить выбор").performClick()
        compose.onNodeWithContentDescription("Показать сеткой").performClick()
        compose.onNodeWithTag("archive-group-expand-PUBLICATION:post").performClick()
        compose.onNodeWithText("two.jpg").assertIsDisplayed()
        compose.onNodeWithTag("archive-group-select-PUBLICATION:post").performClick()
        compose.onNodeWithTag("simple-archive-restore").performClick()
        assertEquals(setOf("two"), restored)
    }

}

package com.domenota.medialoader.ui.screens

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.ui.model.*
import com.domenota.medialoader.core.database.CatalogAssignmentEntity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class DownloadsGroupActionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun collapsedGroupActionsTargetAllThirtyMembersIncludingUnshownChildren() {
        compose.activity.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().clear().commit()
        val rows = (1..30).map { DownloadUiItem("$it", "Image $it", "Фото", null,
            state = DownloadUiState.COMPLETED, groupId = "post") }
        val favorites = mutableListOf<Pair<String, Boolean>>()
        var moved: List<String> = emptyList()
        val deleted = mutableListOf<String>()
        compose.setContent { MaterialTheme { DownloadsScreen(rows,
            catalogAssignments = listOf(CatalogAssignmentEntity("1", null, true)),
            onSetFavorite = { id, favorite -> favorites += id to favorite },
            onMoveGroupToPrivate = { moved = it }, onDeleteGroupFile = { deleted += it },
            onOpenDownload = {}, onCancelDownload = {}, onShareDownload = {}, onHideDownload = {},
            onRetryDownload = {}, onReanalyzeMissing = {}, onRenameDownload = { _, _ -> }) } }
        fun menu() = compose.onNodeWithContentDescription("Действия с группой").performScrollTo().performClick()
        menu()
        compose.onNodeWithText("В избранное").performClick()
        compose.waitForIdle()
        assertEquals(30, favorites.size)
        assertTrue(favorites.all { it.second })
        menu()
        compose.onNodeWithText("Переместить в личное").performClick()
        assertEquals(rows.map { it.id }, moved)
        menu()
        compose.onNodeWithText("Удалить").performClick()
        compose.onNodeWithText("Удалить группу?").assertIsDisplayed()
        compose.onNodeWithText("В корзину").performClick()
        compose.waitForIdle()
        assertEquals(rows.map { it.id }, deleted)
    }
    @Test fun activeGroupDeletionDoesNotHideUnfinishedOrFailedTasks() {
        compose.activity.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().clear().commit()
        val rows = listOf(DownloadUiState.COMPLETED, DownloadUiState.FAILED, DownloadUiState.DOWNLOADING).mapIndexed { index, state ->
            DownloadUiItem("$index", "Image $index", "Фото", null, state = state, groupId = "active-post") }
        val deleted = mutableListOf<String>()
        compose.setContent { MaterialTheme { DownloadsScreen(rows, onDeleteGroupFile = { deleted += it },
            onOpenDownload = {}, onCancelDownload = {}, onShareDownload = {}, onHideDownload = {},
            onRetryDownload = {}, onReanalyzeMissing = {}, onRenameDownload = { _, _ -> }) } }
        compose.onNodeWithContentDescription("Действия с группой").performScrollTo().performClick()
        compose.onNodeWithText("Удалить").performClick()
        compose.onNodeWithText("В корзину").performClick()
        compose.waitForIdle()
        assertEquals(listOf("0"), deleted)
        assertEquals("Успешно: 1 · Ошибок: 2", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
    }

}

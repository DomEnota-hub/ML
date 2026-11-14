package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeOrganizationVisibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun hiddenSectionIsAbsentAndCanBeRestoredWithoutDroppingTheOtherHomeSections() {
        var visible by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                HomeScreen("", {}, {}, {}, {}, recentDownloads = listOf(com.domenota.medialoader.ui.model.DownloadUiItem(
                    "synthetic", "Synthetic photo", "", null, state = com.domenota.medialoader.ui.model.DownloadUiState.COMPLETED)), organizationContent = if (visible) ({
                    Text("На разбор")
                    Text("Коллекции")
                    Text("Сохранить ссылку в коллекцию")
                }) else null)
            }
        }
        compose.onNodeWithText("На разбор").assertDoesNotExist()
        compose.onNodeWithText("Коллекции").assertDoesNotExist()
        compose.onNodeWithText("Сохранить ссылку в коллекцию").assertDoesNotExist()
        compose.onNodeWithText("Последние загрузки").assertExists()
        compose.runOnIdle { visible = true }
        compose.onNodeWithText("На разбор").assertExists()
        compose.onNodeWithText("Коллекции").assertExists()
        compose.onNodeWithText("Последние загрузки").assertExists()
    }
}

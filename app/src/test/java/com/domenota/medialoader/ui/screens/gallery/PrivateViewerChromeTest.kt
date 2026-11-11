package com.domenota.medialoader.ui.screens.gallery

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class PrivateViewerChromeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun privatePhotoHasSameViewerActionsExceptHeart() {
        compose.setContent {
            MaterialTheme {
                GalleryV10Bottom(
                    isPhoto = true, isFavorite = false,
                    onShare = {}, onEdit = {}, onFavorite = {},
                    onDelete = {}, onMenu = {}, showFavorite = false,
                )
            }
        }
        compose.onNodeWithContentDescription("Избранное").assertDoesNotExist()
        compose.onNodeWithContentDescription("Поделиться").assertExists()
        compose.onNodeWithContentDescription("Редактировать").assertExists()
        compose.onNodeWithContentDescription("Удалить").assertExists()
        compose.onNodeWithContentDescription("Действия с файлом").assertExists()
    }

    @Test fun privateLandscapeVideoHasSameHeaderActionsExceptHeart() {
        compose.setContent {
            MaterialTheme {
                GalleryVideoHeader(
                    date = "11 ноября 2026", time = "18:00",
                    landscape = true,
                    onBack = {}, onRotate = {}, onFloating = {},
                    onShare = {}, onEdit = {}, onFavorite = {},
                    onDelete = {}, onMenu = {},
                    showFavorite = false,
                )
            }
        }
        compose.onNodeWithContentDescription("Избранное").assertDoesNotExist()
        compose.onNodeWithContentDescription("Поделиться").assertExists()
        compose.onNodeWithContentDescription("Обрезать").assertExists()
        compose.onNodeWithContentDescription("Удалить").assertExists()
        compose.onNodeWithContentDescription("Повернуть экран").assertExists()
    }
}

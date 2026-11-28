package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.InsertPhoto
import androidx.compose.material.icons.rounded.SmartDisplay
import com.domenota.medialoader.data.preferences.VideoThumbnailMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.ui.theme.LightColors
import com.domenota.medialoader.ui.theme.MediaLoaderColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w393dp-h852dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ThemeModeDialogTest{
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun allThreeModesAreSelectableAndSystemIsDistinct(){
        var selected=""
        compose.setContent{MaterialTheme(colorScheme=LightColors){ThemeModeDialog("system",{}, {selected=it})}}
        compose.onNodeWithText("Светлая").assertExists().performClick()
        compose.runOnIdle{assertEquals("light",selected)}
        compose.onNodeWithText("Тёмная").assertExists().performClick()
        compose.runOnIdle{assertEquals("dark",selected)}
        compose.onNodeWithText("Системная").assertExists().performClick()
        compose.runOnIdle{assertEquals("system",selected)}
    }
    @Test fun darkPreviewKeepsAllModesAvailable(){
        compose.setContent{MaterialTheme(colorScheme=MediaLoaderColors){ThemeModeDialog("dark",{},{})}}
        compose.onNodeWithText("Светлая").assertExists()
        compose.onNodeWithText("Тёмная").assertExists()
        compose.onNodeWithText("Системная").assertExists()
    }

    @Test fun thumbnailChoicesExposeSelectionAndChangeThroughWholeCard(){
        var selected:VideoThumbnailMode?=null
        compose.setContent{MaterialTheme(colorScheme=LightColors){
            SettingsChoiceDialog("Миниатюра видео",VideoThumbnailMode.SOURCE,listOf(
                SettingsChoice(VideoThumbnailMode.SOURCE,"Обложка источника","Оригинальная обложка",Icons.Rounded.InsertPhoto),
                SettingsChoice(VideoThumbnailMode.FRAME,"Кадр из видео","Содержимое видео",Icons.Rounded.SmartDisplay)
            ),{},preview={ThumbnailChoicePreview(it)}){selected=it}
        }}
        compose.onNodeWithText("Обложка источника").assertIsSelected()
        compose.onNodeWithText("Кадр из видео").assertIsNotSelected().performClick()
        compose.runOnIdle{assertEquals(VideoThumbnailMode.FRAME,selected)}
    }

    @Test fun semanticChoiceCardWorksInDarkTheme(){
        var picked=false
        compose.setContent{MaterialTheme(colorScheme=MediaLoaderColors){
            SettingsChoiceCard("Основной","Искать только на основном",false,Icons.Rounded.SmartDisplay,{picked=true})
        }}
        compose.onNodeWithText("Основной").assertIsNotSelected().performClick()
        compose.runOnIdle{assertEquals(true,picked)}
    }
}

package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
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
}

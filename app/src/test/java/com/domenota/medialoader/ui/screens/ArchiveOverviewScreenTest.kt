package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w390dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArchiveOverviewScreenTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun onlyVerifiedWholeFolderNavigationIsEnabled() {
        var opened=0
        var closed=0
        compose.setContent { MaterialTheme {
            ArchiveOverviewScreen(2,{ closed++ },{ opened++ })
        } }
        compose.onNodeWithTag("archive-overview").assertExists()
        compose.onNodeWithText("Архив").assertExists()
        compose.onNodeWithTag("archive-selective-locked").assertIsNotEnabled()
        compose.onNodeWithTag("archive-open-folders").performClick()
        assertEquals(1,opened)
        compose.onNodeWithContentDescription("Вернуться из Архива").performClick()
        assertEquals(1,closed)
    }
}

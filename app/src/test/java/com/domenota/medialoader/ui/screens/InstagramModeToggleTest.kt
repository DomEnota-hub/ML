package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.data.share.InstagramShareSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstagramModeToggleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun togglePersistsAndDoesNotMoveIconOrButton() {
        val context = compose.activity.applicationContext
        InstagramShareSettings.setAutomatic(context, false)
        compose.setContent { MaterialTheme { InstagramModeToggle() } }
        val button = compose.onNodeWithContentDescription("Режим загрузки Instagram")
        val icon = compose.onNodeWithTag("instagram-share-mode-icon", useUnmergedTree = true)
        val bounds = button.fetchSemanticsNode().boundsInRoot
        val iconBounds = icon.fetchSemanticsNode().boundsInRoot
        button.performClick()
        compose.waitForIdle()
        assertTrue(InstagramShareSettings.isAutomatic(context))
        assertEquals(bounds, button.fetchSemanticsNode().boundsInRoot)
        assertEquals(iconBounds, icon.fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("Авто").assertExists()
        button.performClick()
        compose.waitForIdle()
        assertFalse(InstagramShareSettings.isAutomatic(context))
        compose.runOnIdle { InstagramShareSettings.setAutomatic(context, true) }
        compose.onNodeWithText("Авто").assertExists()
        assertEquals("automatic", context.getSharedPreferences("ui", 0).getString("instagram_share_mode", null))
        compose.runOnIdle { InstagramShareSettings.setAutomatic(context, false) }
        compose.onNodeWithText("Ручной").assertExists()
    }
}

package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class PhotoEditorStageOneUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun categoryTabIsAccessibleAndClickable() {
        var taps = 0
        compose.setContent {
            MaterialTheme {
                PhotoCategoryTab("Обрезка", Icons.Rounded.Crop, selected = true) { taps++ }
            }
        }
        compose.onNodeWithText("Обрезка").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, taps) }
    }

    @Test fun inactiveCategoryHasTheSameAction() {
        var taps = 0
        compose.setContent {
            MaterialTheme {
                PhotoCategoryTab("Обрезка", Icons.Rounded.Crop, selected = false) { taps++ }
            }
        }
        compose.onNodeWithText("Обрезка").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, taps) }
    }
}

package com.domenota.medialoader.ui.components

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
@Config(sdk=[34],qualifiers="w320dp-h640dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DuplicateSearchShortcutTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun shortcutIsCompactAndNeverStartsSearchByComposition() {
        var opens=0
        compose.setContent { MaterialTheme { DuplicateSearchShortcut { opens++ } } }
        compose.onNodeWithTag("duplicate-search-shortcut").assertIsDisplayed()
        compose.onNodeWithText("Найти дубликаты").assertIsDisplayed()
        assertEquals(0,opens)
        compose.onNodeWithTag("duplicate-search-shortcut").performClick()
        assertEquals(1,opens)
    }
}

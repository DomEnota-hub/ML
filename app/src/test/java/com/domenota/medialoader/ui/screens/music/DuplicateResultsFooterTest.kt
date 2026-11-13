package com.domenota.medialoader.ui.screens.music

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DuplicateResultsFooterTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun portraitFooterAndButtonFitAboveViewportEnd() = verify(340, 480, 178)
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    @Test fun landscapeFooterAndButtonFitAboveViewportEnd() = verify(640, 220, 178)
    @Test fun noSelectionDisablesTrash() = verify(340, 480, 0)

    @Test fun activityScaffoldReservesNavigationBarAndActionIsClickable() {
        var clicked = 0
        var bottomPx = 0f
        compose.setContent { MaterialTheme {
            bottomPx = with(LocalDensity.current) { 48.dp.toPx() }
            Scaffold(Modifier.fillMaxSize().testTag("activity-window"),
                contentWindowInsets = WindowInsets(left = 0.dp, top = 24.dp, right = 0.dp, bottom = 48.dp)) { padding ->
                Column(Modifier.padding(padding).fillMaxSize().testTag("safe-viewport")) {
                    Spacer(Modifier.weight(1f))
                    DuplicateResultsFooter(2,"15,85 МБ",false) { clicked++ }
                }
            }
        } }
        val window = compose.onNodeWithTag("activity-window").fetchSemanticsNode().boundsInRoot
        val safe = compose.onNodeWithTag("safe-viewport").fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithTag("duplicates-trash-action").assertIsDisplayed()
        val bounds = action.fetchSemanticsNode().boundsInRoot
        assertTrue(safe.bottom <= window.bottom - bottomPx + 1f)
        assertTrue(bounds.bottom <= safe.bottom + 1f)
        action.performClick()
        compose.runOnIdle { assertEquals(1,clicked) }
    }

    private fun verify(width: Int, height: Int, count: Int) {
        compose.setContent { MaterialTheme {
            Column(Modifier.width(width.dp).height(height.dp).testTag("viewport")) {
                Spacer(Modifier.weight(1f))
                DuplicateResultsFooter(count, "1,21 ГБ", false) {}
            }
        } }
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("duplicates-footer").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithTag("duplicates-trash-action").assertIsDisplayed()
        val bounds = action.fetchSemanticsNode().boundsInRoot
        assertTrue(footer.bottom <= viewport.bottom + 1f)
        assertTrue(bounds.top >= footer.top && bounds.bottom <= footer.bottom + 1f)
        compose.onNodeWithTag("duplicates-count").assertTextEquals(count.toString())
        compose.onNodeWithTag("duplicates-bytes").assertTextEquals("1,21 ГБ")
        if (count == 0) action.assertIsNotEnabled() else action.assertIsEnabled()
    }
}

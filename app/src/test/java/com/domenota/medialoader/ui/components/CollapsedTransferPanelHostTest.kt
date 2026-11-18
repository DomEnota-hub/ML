package com.domenota.medialoader.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.catalog.visibility.VisibilityExecutionPhase
import com.domenota.medialoader.data.catalog.visibility.VisibilityExecutionSnapshot
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
class CollapsedTransferPanelHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val player = mutableStateOf(false)
    private var opened = false
    private var selected = false

    // A 12dp spacer regression, double-counted navigation inset, or ignoring
    // measured player height must fail these pixel-boundary assertions.
    private fun show(
        navInset: Dp = 24.dp,
        width: Dp = 320.dp,
        height: Dp = 560.dp,
        fontScale: Float = 1f,
        imeInset: Dp = 0.dp,
        sideInset: Dp = 0.dp,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(width, height).testTag("viewport")) {
                        Scaffold(
                            contentWindowInsets = WindowInsets(0, 0, 0, 0),
                            bottomBar = {
                                Column(Modifier.testTag("chrome")) {
                                    // Service-free player slot: its measured height changes
                                    // with font scale, just as the real AudioMiniPlayer does.
                                    if (player.value) Text("Аудио\nИсполнитель", Modifier
                                        .fillMaxWidth().padding(8.dp).testTag("player"))
                                    NavigationBar(windowInsets = WindowInsets(bottom = navInset)) {
                                        NavigationBarItem(selected = true, onClick = { selected = true },
                                            icon = { Text("↓") }, label = { Text("Загрузки") })
                                    }
                                }
                            },
                        ) { padding ->
                            CollapsedTransferPanelHost(
                                chromeBottom = padding.calculateBottomPadding(),
                                safeInsets = WindowInsets(left = sideInset, right = sideInset,
                                    bottom = maxOf(navInset, imeInset)),
                            ) {
                                FolderVisibilityMiniPanel(
                                    VisibilityExecutionSnapshot(phase = VisibilityExecutionPhase.RUNNING,
                                        confirmed = 1, total = 4),
                                    onOpen = { opened = true },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag)
        .fetchSemanticsNode().boundsInRoot

    private fun assertAdjacent() {
        compose.onNodeWithTag("visibility-transfer-compact").assertIsDisplayed()
        val card = bounds("visibility-transfer-compact")
        val chrome = bounds("chrome")
        assertEquals("Card must touch the measured top of bottom chrome", chrome.top, card.bottom, 1f)
        assertTrue("Card stays inside viewport", card.top >= bounds("viewport").top)
        compose.onNodeWithText("1/4").assertIsDisplayed()
    }

    @Test fun noPlayerCardTouchesNavigationAndBothActionsRemainClickable() {
        show()
        assertAdjacent()
        compose.onNodeWithText("Открыть").performClick()
        compose.onNodeWithText("Загрузки").performClick()
        compose.runOnIdle { assertTrue(opened); assertTrue(selected) }
    }

    @Test fun playerAppearingAndDisappearingRemeasuresBoundary() {
        show(navInset = 48.dp)
        assertAdjacent()
        val idleBottom = bounds("visibility-transfer-compact").bottom
        compose.runOnIdle { player.value = true }
        assertAdjacent()
        assertTrue(bounds("visibility-transfer-compact").bottom < idleBottom)
        assertTrue(bounds("visibility-transfer-compact").bottom <= bounds("player").top)
        compose.runOnIdle { player.value = false }
        assertAdjacent()
    }

    @Test fun narrowSmallLandscapeWithLargeFontAndSideInsetsKeepsWholeCardAbovePlayer() {
        player.value = true
        show(width = 360.dp, height = 300.dp, fontScale = 1.5f, sideInset = 24.dp)
        assertAdjacent()
        val card = bounds("visibility-transfer-compact")
        val viewport = bounds("viewport")
        val expectedMargin = viewport.width * (40f / 360f) // 24dp cutout + 16dp card margin
        assertEquals(expectedMargin, card.left - viewport.left, 1f)
        assertEquals(expectedMargin, viewport.right - card.right, 1f)
        compose.onNodeWithText("Открыть").assertIsDisplayed()
    }

    @Test fun keyboardWinsOverChromeWithoutAddingChromeHeightTwice() {
        show(imeInset = 240.dp)
        compose.onNodeWithTag("visibility-transfer-compact").assertIsDisplayed()
        val viewport = bounds("viewport")
        val expectedBottom = viewport.bottom - viewport.height * (240f / 560f)
        assertEquals(expectedBottom, bounds("visibility-transfer-compact").bottom, 1f)
        assertTrue(bounds("visibility-transfer-compact").bottom < bounds("chrome").top)
    }
}

package com.domenota.medialoader.ui.screens.gallery

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], qualifiers="w720dp-h390dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GalleryVideoHeaderActionPlacementTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    private fun verifyOrder(landscape:Boolean) {
        var rotate=0
        var floating=0
        compose.setContent { MaterialTheme {
            GalleryVideoHeader(
                date="20 ноября",time="21:00",landscape=landscape,
                onBack={},onRotate={rotate++},onFloating={floating++},onShare={},
                onEdit={},onFavorite={},onDelete={},onMenu={},
            )
        } }
        val pip=compose.onNodeWithContentDescription("В оконный режим")
        val rotation=compose.onNodeWithContentDescription("Повернуть экран")
        pip.assertIsDisplayed()
        rotation.assertIsDisplayed()
        assertTrue("Floating action must precede rotation in both orientations",
            pip.fetchSemanticsNode().boundsInRoot.left <
                rotation.fetchSemanticsNode().boundsInRoot.left)
        pip.performClick()
        rotation.performClick()
        compose.runOnIdle {
            assertEquals(1,floating)
            assertEquals(1,rotate)
        }
    }

    @Test fun portraitKeepsBothActionsAndSwapsOnlyTheirPosition()=verifyOrder(false)
    @Test fun landscapeKeepsBothActionsAndSwapsOnlyTheirPosition()=verifyOrder(true)
}

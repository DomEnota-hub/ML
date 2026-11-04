package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.editor.TextLayer
import com.domenota.medialoader.ui.components.TextLayerSelectionOverlay
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class VideoTextFrameGestureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun draggingFrameMovesTextAndClosesOneGestureTransaction() {
        var layer by mutableStateOf(TextLayer(text = "A", size = .2f))
        var started = 0; var ended = 0
        compose.setContent { MaterialTheme { Box(Modifier.size(300.dp, 200.dp)) {
            TextLayerSelectionOverlay(300, 200, layer, { layer = it }, cornerActions = true,
                onGestureStart = { started++ }, onGestureEnd = { ended++ })
        } } }
        compose.onNodeWithContentDescription("Рамка надписи: перемещение, размер и поворот")
            .performTouchInput { down(center); moveBy(Offset(width * .1f, height * .1f)); up() }
        compose.runOnIdle {
            assertEquals(.6f, layer.centerX, .02f); assertEquals(.6f, layer.centerY, .02f)
            assertEquals(1, started); assertEquals(1, ended)
        }
    }
}

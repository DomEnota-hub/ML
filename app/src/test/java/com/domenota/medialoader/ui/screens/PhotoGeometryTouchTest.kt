package com.domenota.medialoader.ui.screens

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.data.editor.PhotoGeometry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PhotoGeometryTouchTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun enlargedCornerTargetPreservesGrabOffsetAndCanBeApplied() {
        val source=Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888)
        var applied: PhotoGeometry?=null
        compose.setContent { MaterialTheme { PhotoGeometryDialog(source,PhotoGeometry(),{applied=it},{}) } }
        compose.onNodeWithText("Свободно").performScrollTo().performClick()
        val canvas=compose.onNodeWithTag("geometry-canvas")
        val bounds=canvas.fetchSemanticsNode().boundsInRoot
        val rect=photoCanvasRect(bounds.width,bounds.height,200,100)
        val density=compose.activity.resources.displayMetrics.density
        // 108dp from the visible corner: outside the former 92dp radius, inside 112dp.
        val start=Offset(rect.x+90*density,rect.y+60*density)
        val movement=Offset(10*density,10*density)
        canvas.performTouchInput { down(start);moveTo(start+movement,100);up() }
        compose.onNodeWithText("Готово").performClick()
        compose.runOnIdle {
            val point=requireNotNull(applied).corners[0]
            assertEquals(movement.x/rect.width,point.x,.015f)
            assertEquals(movement.y/rect.height,point.y,.015f)
        }
        // Leave bitmap lifecycle to the test process while the composable is still present.
    }
}

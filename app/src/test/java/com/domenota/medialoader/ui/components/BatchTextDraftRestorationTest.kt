package com.domenota.medialoader.ui.components

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.data.editor.TextLayer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w390dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BatchTextDraftRestorationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun unappliedTextStyleTransformAndSelectedLayerSurviveSavedStateRecreation() {
        val initial=listOf(TextLayer(id="first",text="Первый"),TextLayer(id="second",text="Второй",rotation=35f))
        val image=Bitmap.createBitmap(320,400,Bitmap.Config.ARGB_8888)
        var applied:List<TextLayer>?=null
        val restoration=StateRestorationTester(compose)
        restoration.setContent{MaterialTheme{
            EditorTextDialog(initial=initial,loadPreview={image},onApply={applied=it},onDismiss={},fitInside=true)
        }}
        compose.onNodeWithText("Изменить").performClick()
        compose.onNodeWithText("Текст 2").performClick()
        compose.onNodeWithTag("editor-text-input").performTextReplacement("Черновик до поворота")
        compose.onNodeWithText("Стиль").performClick()
        compose.onNodeWithText("Жирный").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Свернуть панель").performClick()
        val frame=compose.onNodeWithContentDescription("Рамка надписи: перемещение, размер и поворот")
        compose.waitUntil(15_000){compose.onAllNodesWithContentDescription("Рамка надписи: перемещение, размер и поворот")
            .fetchSemanticsNodes().isNotEmpty()}
        fun accessibilityAction(label:String){
            val action=frame.fetchSemanticsNode().config[SemanticsActions.CustomActions].first{it.label==label}
            compose.runOnIdle{action.action()}
            compose.waitForIdle()
        }
        accessibilityAction("Увеличить текст")
        accessibilityAction("Сбросить поворот")
        frame.performTouchInput{swipe(center,center+Offset(30f,20f),400)}
        compose.onNodeWithText("Изменить").performClick()
        compose.onNodeWithText("Стиль").performClick()
        compose.runOnIdle{assertNull(applied)}
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Текст 2").assertIsSelected()
        compose.onNodeWithText("Цвет текста").assertExists()
        compose.onNodeWithText("Клавиатура").performClick()
        compose.onNodeWithTag("editor-text-input").assertTextContains("Черновик до поворота")
        compose.onNodeWithText("Готово").performClick()
        compose.runOnIdle {
            val layers=requireNotNull(applied)
            assertEquals(initial[0],layers[0])
            val restored=layers[1]
            assertEquals("second",restored.id)
            assertEquals("Черновик до поворота",restored.text)
            assertTrue(restored.bold)
            assertEquals(initial[1].size*1.1f,restored.size,.0001f)
            assertEquals(0f,restored.rotation,.0001f)
            assertTrue("Edited placement lost",restored.centerX>.5f&&restored.centerY>.5f)
        }
    }
}

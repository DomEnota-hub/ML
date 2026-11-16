package com.domenota.medialoader.ui.screens.gallery

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.ui.theme.LightColors
import com.domenota.medialoader.ui.theme.MediaLoaderColors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w320dp-h640dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VisualDuplicateStoppedPanelTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun compact(dark:Boolean){
        var resumes=0;var results=0
        val scheme=if(dark)MediaLoaderColors else LightColors
        compose.setContent{MaterialTheme(colorScheme=scheme){VisualDuplicateStoppedPanel(true,true,{resumes++},{results++})}}
        compose.onNodeWithText("Поиск остановлен").assertIsDisplayed()
        compose.onNodeWithText("Проверенные файлы и результаты сохранены.").assertIsDisplayed()
        val resume=compose.onNodeWithText("Продолжить")
        val result=compose.onNodeWithText("Результаты")
        resume.assertIsDisplayed().performClick();result.assertIsDisplayed().performClick()
        for(node in listOf(resume,result))node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){action->
            val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            assertTrue(action(layouts))
            assertTrue(layouts.isNotEmpty())
            // Robolectric native text sometimes flags didOverflowWidth even when
            // the entire one-line glyph run is inside the 220px text bounds
            // (145.5px intrinsic / 144px lineRight). Verify actual extents
            // rather than relying solely on that contradictory flag.
            assertTrue("Single-line label must fit: "+layouts.joinToString { "lines=${it.lineCount},widthOverflow=${it.didOverflowWidth},heightOverflow=${it.didOverflowHeight},intrinsic=${it.multiParagraph.maxIntrinsicWidth},left=${it.getLineLeft(0)},right=${it.getLineRight(0)},size=${it.size}" },
                layouts.all {
                    it.lineCount==1 && !it.didOverflowHeight &&
                    it.getLineLeft(0)>=-1f &&
                    it.getLineRight(0)<=it.size.width.toFloat()+1f &&
                    it.multiParagraph.maxIntrinsicWidth<=it.size.width.toFloat()+1f
                })
        }
        assertEquals(1,resumes);assertEquals(1,results)
        val first=resume.fetchSemanticsNode().boundsInRoot
        val second=result.fetchSemanticsNode().boundsInRoot
        assertEquals(first.top,second.top,1f)
        assertEquals(first.height,second.height,1f)
        assertTrue(first.right<second.left)
        assertTrue(second.right<=compose.activity.resources.displayMetrics.widthPixels)
        assertEquals(if(dark)Color(0xFFFFBD19) else Color(0xFF762DFF),scheme.primary)
    }
    @Test fun compactDarkStopActionsAreSingleRowAndUseExistingTheme()=compact(true)
    @Test fun compactLightStopActionsAreSingleRowAndUseExistingTheme()=compact(false)
    @Test fun resultsRemainAvailableWhileResumeRequiresFreshCatalog(){
        var results=0
        compose.setContent{MaterialTheme{VisualDuplicateStoppedPanel(false,true,{error("Stale catalog resumed")},{results++})}}
        compose.onNodeWithText("Продолжить").assertIsNotEnabled()
        compose.onNodeWithText("Результаты").assertIsEnabled().performClick()
        assertEquals(1,results)
    }
    @Test fun accessRevocationDisablesBothActions(){
        compose.setContent{MaterialTheme{VisualDuplicateStoppedPanel(false,false,{error("No access")},{error("No access")})}}
        compose.onNodeWithText("Продолжить").assertIsNotEnabled()
        compose.onNodeWithText("Результаты").assertIsNotEnabled()
    }
}

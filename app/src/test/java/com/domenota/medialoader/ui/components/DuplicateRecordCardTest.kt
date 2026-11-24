package com.domenota.medialoader.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w393dp-h852dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DuplicateRecordCardTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun keeperAndOtherCardHaveEqualBoundsAndIndependentSelectionInBothThemes(){
        val dark=mutableStateOf(true)
        var selected by mutableStateOf(false)
        compose.setContent {
            MaterialTheme(colorScheme=if(dark.value)darkColorScheme() else lightColorScheme()){
                Column(Modifier.fillMaxWidth().padding(horizontal=16.dp)){
                    DuplicateRecordCard(true,"keeper"){
                        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                            Text("Остаётся",Modifier.weight(1f))
                            Checkbox(false,{},enabled=false,modifier=Modifier.testTag("keeper-selection"))
                        }
                    }
                    DuplicateRecordCard(false,"other"){
                        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                            Text("Другой файл",Modifier.weight(1f))
                            Checkbox(selected,{selected=it},modifier=Modifier.testTag("other-selection"))
                        }
                    }
                }
            }
        }
        fun assertGeometry(){
            val keeper=compose.onNodeWithTag("keeper").fetchSemanticsNode().boundsInRoot
            val other=compose.onNodeWithTag("other").fetchSemanticsNode().boundsInRoot
            assertEquals(keeper.left,other.left,1f)
            assertEquals(keeper.right,other.right,1f)
            assertEquals(keeper.height,other.height,1f)
            compose.onNodeWithTag("keeper-selection").assertIsOff().assertIsNotEnabled()
        }
        assertGeometry()
        compose.onNodeWithTag("other-selection").performClick().assertIsOn()
        compose.runOnIdle{dark.value=false}
        assertGeometry()
        compose.onNodeWithTag("other-selection").assertIsOn()
    }
}

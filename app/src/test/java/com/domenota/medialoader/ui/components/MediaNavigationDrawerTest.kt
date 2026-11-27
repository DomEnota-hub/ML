package com.domenota.medialoader.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import com.domenota.medialoader.ui.screens.DrawerSettingsScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.share.InstagramShareSettings
import com.domenota.medialoader.ui.navigation.DrawerEntry
import com.domenota.medialoader.ui.navigation.DrawerConfiguration
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w393dp-h852dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaNavigationDrawerTest{
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private var edgePx=0f
    private fun host()=compose.onNodeWithTag("ml-drawer-host")
    private fun open(){
        host().performTouchInput{
            swipe(Offset(edgePx,center.y),Offset(width*.75f,center.y),durationMillis=500)
        }
        compose.onNodeWithTag("ml-drawer-panel").assertExists()
    }
    @Composable private fun TestHost(allowed:Boolean=true,identity:Int=0,
        active:DrawerEntry=DrawerEntry.MUSIC,onSelect:(DrawerEntry)->Unit={},
        journalUnlocked:Boolean=false, configuration:DrawerConfiguration=DrawerConfiguration.DEFAULT){
        val density=LocalDensity.current
        val direction=LocalLayoutDirection.current
        val edge=WindowInsets.systemGestures.getLeft(density,direction)+with(density){4.dp.toPx()}
        SideEffect{edgePx=edge}
        MediaNavigationDrawer(identity,allowed,active,journalUnlocked=journalUnlocked,onSelect=onSelect,configuration=configuration){
            Box(Modifier.fillMaxSize()){Text("Содержимое")}
        }
    }

    @Test fun panelUsesEightyPercentAndNoVersionAndClosesOutside(){
        compose.setContent{MaterialTheme{TestHost()}}
        open()
        val parent=host().fetchSemanticsNode().boundsInRoot
        val panel=compose.onNodeWithTag("ml-drawer-panel").fetchSemanticsNode().boundsInRoot
        assertEquals(parent.width*.8f,panel.width,2f)
        compose.onNodeWithText("MediaLoader").assertExists()
        compose.onNodeWithText("Библиотека").assertDoesNotExist()
        compose.onNodeWithTag("drawer-entry-EQUALIZER").assertDoesNotExist()
        compose.onNodeWithText("1.7.226").assertDoesNotExist()
        compose.onNodeWithTag("drawer-entry-MUSIC").assertIsSelected()
        compose.onNodeWithTag("drawer-entry-FAVORITES").assertIsEnabled()
        compose.onNodeWithTag("drawer-entry-JOURNAL").assertDoesNotExist()
        host().performTouchInput{click(Offset(width*.95f,center.y))}
        compose.onNodeWithTag("ml-drawer-panel").assertDoesNotExist()
    }

    @Test fun forbiddenStateClosesAndRejectsGesturesWhileMidContentRemainsFree(){
        var allowed by mutableStateOf(true)
        var identity by mutableIntStateOf(0)
        compose.setContent{MaterialTheme{TestHost(allowed,identity)}}
        host().performTouchInput{swipe(Offset(width*.3f,center.y),Offset(width*.9f,center.y))}
        compose.onNodeWithTag("ml-drawer-panel").assertDoesNotExist()
        open()
        compose.runOnIdle{allowed=false}
        compose.onNodeWithTag("ml-drawer-panel").assertDoesNotExist()
        host().performTouchInput{swipe(Offset(edgePx,center.y),Offset(width*.75f,center.y))}
        compose.onNodeWithTag("ml-drawer-panel").assertDoesNotExist()
        compose.runOnIdle{allowed=true}
        open()
        compose.runOnIdle{identity++}
        compose.onNodeWithTag("ml-drawer-panel").assertDoesNotExist()
    }

    @Test fun downloadModeUsesExistingPreferenceAndBackClosesWithoutChangingRoute(){
        val context=compose.activity
        val old=InstagramShareSettings.isAutomatic(context)
        try{
            InstagramShareSettings.setAutomatic(context,false)
            var selected:DrawerEntry?=null
            compose.setContent{MaterialTheme{TestHost(onSelect={selected=it})}}
            open()
            val toggle=compose.onNodeWithTag("drawer-download-mode")
            toggle.assertIsOff().performClick().assertIsOn()
            compose.runOnIdle{assertTrue(InstagramShareSettings.isAutomatic(context));assertNull(selected)}
            compose.onNodeWithTag("ml-drawer-panel").assertExists()
            compose.runOnIdle{context.onBackPressedDispatcher.onBackPressed()}
            compose.onNodeWithTag("ml-drawer-panel").assertDoesNotExist()
            open()
            compose.onNodeWithTag("drawer-entry-GALLERY").performClick()
            compose.runOnIdle{assertEquals(DrawerEntry.GALLERY,selected)}
            compose.onNodeWithTag("ml-drawer-panel").assertDoesNotExist()
        }finally{InstagramShareSettings.setAutomatic(context,old)}
    }

    @Test fun reverseSwipeClosesAndVerticalEdgeGestureDoesNotOpen(){
        compose.setContent{MaterialTheme{TestHost()}}
        host().performTouchInput{
            swipe(Offset(edgePx,center.y),Offset(edgePx,center.y*.3f),durationMillis=500)
        }
        compose.onNodeWithTag("ml-drawer-panel").assertDoesNotExist()
        open()
        host().performTouchInput{
            swipe(Offset(width*.65f,center.y),Offset(width*.1f,center.y),durationMillis=500)
        }
        compose.onNodeWithTag("ml-drawer-panel").assertDoesNotExist()
    }

    @Test fun multipleChildOverlaysVetoIndependentlyAndOldOwnerCannotAdmitNewScreen(){
        val first=DrawerAdmission()
        val second=DrawerAdmission()
        val menu=Any()
        val dialog=Any()
        assertFalse(first.admitted)
        first.update(true)
        assertTrue(first.admitted)
        first.block(menu)
        first.block(dialog)
        assertFalse(first.admitted)
        first.unblock(menu)
        assertFalse(first.admitted)
        first.unblock(dialog)
        assertTrue(first.admitted)
        assertFalse(second.admitted)
        second.update(true)
        first.update(false)
        assertTrue(second.admitted)
    }

    @Test fun beforeNavigationPreservesScreenStateAndRejectsBlockedOrFailedExit()=kotlinx.coroutines.runBlocking{
        val admission=DrawerAdmission()
        var calls=0
        admission.setExitGuard{calls++;false}
        assertFalse(admission.prepareNavigation())
        assertEquals(0,calls)
        admission.update(true)
        admission.setExitGuard{calls++;assertFalse(admission.admitted);false}
        assertFalse(admission.prepareNavigation())
        assertEquals(1,calls)
        assertTrue(admission.admitted)
        admission.setExitGuard{calls++;true}
        assertTrue(admission.prepareNavigation())
        assertEquals(2,calls)
        assertTrue(admission.admitted)
    }
    @Test fun libraryRowsNavigateAndJournalAppearsOnlyWhileUnlocked(){
        var unlocked by mutableStateOf(false)
        var selected:DrawerEntry?=null
        var configuration by mutableStateOf(DrawerConfiguration.DEFAULT)
        compose.setContent{MaterialTheme{TestHost(onSelect={selected=it},journalUnlocked=unlocked,configuration=configuration)}}
        open()
        compose.onNodeWithTag("drawer-entry-JOURNAL").assertDoesNotExist()
        for(entry in listOf(DrawerEntry.FAVORITES,DrawerEntry.PRIVATE,DrawerEntry.ARCHIVE,
            DrawerEntry.COLLECTIONS,DrawerEntry.TRASH)){
            val row=compose.onNodeWithTag("drawer-entry-${entry.name}")
            row.assertIsEnabled().performScrollTo().performClick()
            compose.runOnIdle{assertEquals(entry,selected)}
            open()
        }
        compose.runOnIdle{unlocked=true}
        compose.onNodeWithTag("drawer-entry-JOURNAL").assertExists().assertIsEnabled()
        compose.runOnIdle{unlocked=false}
        compose.onNodeWithTag("drawer-entry-JOURNAL").assertDoesNotExist()
        compose.runOnIdle {
            configuration = configuration.setVisible(DrawerEntry.HOME,false)
                .setVisible(DrawerEntry.EQUALIZER,true)
                .setVisible(DrawerEntry.DIAGNOSTICS,true)
                .move(DrawerEntry.EQUALIZER,DrawerEntry.HOME)
        }
        compose.onNodeWithTag("drawer-entry-HOME").assertExists().assertIsEnabled()
        compose.onNodeWithTag("drawer-entry-EQUALIZER").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("drawer-entry-DIAGNOSTICS").assertDoesNotExist()
        compose.onNodeWithTag("drawer-entry-EQUALIZER").performClick()
        compose.runOnIdle{assertEquals(DrawerEntry.EQUALIZER,selected)}
        open()
        compose.runOnIdle{unlocked=true}
        compose.onNodeWithTag("drawer-entry-DIAGNOSTICS").assertDoesNotExist()
        compose.onNodeWithTag("drawer-entry-JOURNAL").assertExists().assertIsEnabled()
        compose.runOnIdle{unlocked=false}
        compose.onNodeWithTag("drawer-entry-DIAGNOSTICS").assertDoesNotExist()
    }
    @Test fun configurationEditorSavesVisibilityOrderAndResetsWhileSettingsStaysEnabled(){
        var configuration by mutableStateOf(DrawerConfiguration.DEFAULT)
        compose.setContent { MaterialTheme {
            DrawerSettingsScreen(configuration,true,{configuration=it},{})
        } }
        compose.onNodeWithTag("drawer-config-DIAGNOSTICS").assertDoesNotExist()
        compose.onNodeWithTag("drawer-config-switch-DIAGNOSTICS").assertDoesNotExist()
        compose.onNodeWithTag("drawer-config-handle-DIAGNOSTICS").assertDoesNotExist()
        compose.onNodeWithTag("drawer-config-JOURNAL").assertExists()
        for(entry in listOf(DrawerEntry.HOME,DrawerEntry.GALLERY,DrawerEntry.MUSIC)){
            compose.onNodeWithTag("drawer-config-switch-${entry.name}").performScrollTo().assertIsOn().assertIsNotEnabled()
        }
        compose.runOnIdle { assertTrue(listOf(DrawerEntry.HOME,DrawerEntry.GALLERY,DrawerEntry.MUSIC).all { it in configuration.visible }) }
        val handle=compose.onNodeWithTag("drawer-config-handle-DOWNLOADS").performScrollTo()
        val action=handle.fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label=="Выше" }
        compose.runOnIdle { assertTrue(action.action()) }
        compose.runOnIdle { assertEquals(DrawerEntry.DOWNLOADS,configuration.order.first()) }
        compose.onNodeWithTag("drawer-config-switch-SETTINGS").assertIsOn().assertIsNotEnabled()
        compose.onNodeWithTag("drawer-config-switch-EQUALIZER").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertTrue(DrawerEntry.EQUALIZER in configuration.visible) }
        compose.onNodeWithTag("drawer-config-reset").performClick()
        compose.runOnIdle { assertEquals(DrawerConfiguration.DEFAULT,configuration) }
        compose.onNodeWithTag("drawer-config-switch-HOME").performScrollTo().assertIsOn()
    }

    @Test fun advancedDrawerEditorHidesAndRestoresExistingSelections(){
        var advanced by mutableStateOf(false)
        var configuration by mutableStateOf(DrawerConfiguration.DEFAULT.setVisible(DrawerEntry.BULK_DOWNLOAD,true).setVisible(DrawerEntry.VK_IMAGES,true))
        val initial=configuration
        compose.setContent { MaterialTheme {
            DrawerSettingsScreen(configuration,true,{configuration=it},{},advancedToolsVisible=advanced)
        } }
        compose.onNodeWithTag("drawer-config-BULK_DOWNLOAD").assertDoesNotExist()
        compose.onNodeWithTag("drawer-config-VK_IMAGES").assertDoesNotExist()
        compose.runOnIdle{advanced=true}
        compose.onNodeWithTag("drawer-config-switch-BULK_DOWNLOAD").performScrollTo().assertIsOn()
        compose.onNodeWithTag("drawer-config-switch-VK_IMAGES").performScrollTo().assertIsOn()
        compose.runOnIdle{advanced=false;assertEquals(initial,configuration)}
        compose.onNodeWithTag("drawer-config-VK_IMAGES").assertDoesNotExist()
    }

    @Test fun dividerEditorAddsMovesAndRemovesUsingExistingHandle(){
        var configuration by mutableStateOf(DrawerConfiguration.DEFAULT)
        compose.setContent { MaterialTheme {
            DrawerSettingsScreen(configuration,true,{configuration=it},{})
        } }
        compose.onNodeWithTag("drawer-config-add-divider").performClick()
        compose.runOnIdle{assertTrue("DIVIDER_3" in configuration.layout)}
        val handle=compose.onNodeWithTag("drawer-config-handle-DIVIDER_3").performScrollTo()
        val old=configuration.layout.indexOf("DIVIDER_3")
        val action=handle.fetchSemanticsNode().config[SemanticsActions.CustomActions].first{it.label=="Выше"}
        compose.runOnIdle{assertTrue(action.action());assertTrue(configuration.layout.indexOf("DIVIDER_3")<old)}
        compose.onNodeWithTag("drawer-config-reset").performClick()
        compose.runOnIdle{assertEquals(DrawerConfiguration.DEFAULT,configuration)}
    }
}

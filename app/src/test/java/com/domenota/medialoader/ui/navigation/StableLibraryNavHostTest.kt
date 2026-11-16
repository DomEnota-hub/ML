package com.domenota.medialoader.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@LooperMode(LooperMode.Mode.PAUSED)
class StableLibraryNavHostTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun routeGraphSurvivesRootUpdatesAndContentRemainsFresh(){
        var value by mutableIntStateOf(1)
        lateinit var nav:NavHostController
        compose.setContent {
            nav=rememberNavController()
            val current=value
            StableLibraryNavHost(nav,"home",routes=listOf(
                LibraryRouteContent("home"){Text("home:$current")},
                LibraryRouteContent("detail/{id}"){entry->Text("detail:${entry.arguments?.getString("id")}:$current")}
            ))
        }
        compose.onNodeWithText("home:1").assertExists()
        val graph=compose.runOnIdle{nav.graph}
        compose.runOnIdle{nav.navigate("detail/test")};compose.waitForIdle()
        compose.onNodeWithText("detail:test:1").assertExists()
        compose.runOnIdle{value=2};compose.waitForIdle()
        compose.onNodeWithText("detail:test:2").assertExists()
        compose.runOnIdle{assertSame(graph,nav.graph);assertTrue(nav.popBackStack())};compose.waitForIdle()
        compose.onNodeWithText("home:2").assertExists()
        compose.runOnIdle{assertSame(graph,nav.graph)}
    }
}

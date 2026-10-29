package com.domenota.medialoader.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class LibraryReturnNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun settingsReturnKeepsExistingLibrarySelectionAcrossRepeatedVisits() {
        compose.setContent {
            val nav = rememberNavController()
            NavHost(nav, startDestination = AppDestination.Home.route) {
                composable(AppDestination.Home.route) {
                    var selection by rememberSaveable { mutableIntStateOf(0) }
                    Column {
                        Text("Выбор: $selection")
                        Button({ selection++ }) { Text("Выбрать альбом") }
                        Button({ nav.navigate(AppDestination.Settings.route) }) { Text("Открыть настройки") }
                    }
                }
                composable(AppDestination.Settings.route) {
                    Button({ returnToLibrary(nav) }) { Text("В библиотеку") }
                }
            }
        }
        compose.onNodeWithText("Выбрать альбом").performClick()
        repeat(2) {
            compose.onNodeWithText("Открыть настройки").performClick()
            compose.onNodeWithText("В библиотеку").performClick()
            compose.onNodeWithText("Выбор: 1").assertExists()
        }
    }
}

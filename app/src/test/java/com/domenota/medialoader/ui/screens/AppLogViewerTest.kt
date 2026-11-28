package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
class AppLogViewerTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun darkAndLightExposeSixFiltersAndFooterActions() {
        var dark by mutableStateOf(true)
        val actions=mutableListOf<String>()
        compose.setContent {
            MaterialTheme(colorScheme=if(dark)darkColorScheme() else lightColorScheme()) {
                Column(Modifier.fillMaxSize()) {
                    AppLogViewer("",true,{},Modifier.weight(1f))
                    JournalFooter({actions.add("refresh")},{actions.add("clear")},{actions.add("export")},{actions.add("close")})
                }
            }
        }
        for (theme in listOf(true,false)) {
            compose.runOnIdle { dark=theme }
            for (name in listOf("Все","Ошибки","Внимание","Успешные","Информация","Отладка"))
                compose.onNodeWithText(name).assertExists()
            compose.onNodeWithText("Понятный").assertExists()
            compose.onNodeWithText("Технический").assertExists()
            compose.onNodeWithText("Обновить").performClick()
            compose.onNodeWithText("Очистить").performClick()
            compose.onNodeWithText("Экспорт").performClick()
            compose.onNodeWithText("Закрыть").performClick()
        }
        assertEquals(listOf("refresh","clear","export","close","refresh","clear","export","close"),actions)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun readableDetailsCopyOriginalAndSearchModeActuallyRespond() {
        val original = "2026-11-27 09:00:01 [DEBUG] [DownloadProgress] [thread=worker] id=a · bytes=2 · total=10"
        var readable by mutableStateOf(true)
        var copied: String? = null
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppLogViewer(original, readable, { readable = it }, Modifier.fillMaxSize(), onCopy = { copied = it })
            }
        }
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("Получение данных файла").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Получение данных файла").performClick()
        compose.onNodeWithContentDescription("Копировать запись").performClick()
        compose.runOnIdle { assertEquals(original, copied) }
        compose.onNodeWithText("Технический").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText(original).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Общие пояснения:", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Сначала старые. Изменить порядок").performClick()
        // Selection runs on Dispatchers.Default, outside the Compose test clock.
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("Сначала новые", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Сначала новые. Изменить порядок").assertExists()
        compose.onNodeWithText("Сначала новые", useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("несуществующее событие")
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("Нет записей по выбранным условиям.").fetchSemanticsNodes().isNotEmpty()
        }
    }
    @Test fun memoryOnlySearchSurvivesLeavingJournalAndRevocationClearsIt() {
        val session=JournalViewerState()
        var visible by mutableStateOf(true)
        compose.setContent {
            MaterialTheme { if(visible)AppLogViewer("",true,{},Modifier.fillMaxSize(),state=session) }
        }
        compose.onNode(hasSetTextAction()).performTextInput("сохранённый поиск")
        compose.runOnIdle{visible=false}
        compose.runOnIdle{visible=true}
        compose.onNode(hasSetTextAction()).assertTextContains("сохранённый поиск")
        compose.runOnIdle{session.clear()}
        compose.onNode(hasSetTextAction()).assertTextContains("")
        compose.runOnIdle{assertEquals("",session.query)}
    }

}

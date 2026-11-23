package com.domenota.medialoader.ui.screens.music

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.data.library.DuplicateBackgroundScan.Outcome
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
class MusicScanModeControlsTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun savedMatchingModeAlwaysContinuesInsteadOfOpeningScopePicker(){
        assertTrue(shouldResumeMusicScan(true,false,false))
        assertTrue(shouldResumeMusicScan(true,true,true))
        assertFalse(shouldResumeMusicScan(true,true,false))
        assertFalse(shouldResumeMusicScan(false,false,false))
    }
    @Test fun resultGroupsUseCorrectRussianGrammaticalForm(){
        assertEquals("Найдена 1 группа",visualMusicGroupLabel(1))
        assertEquals("Найдено 2 группы",visualMusicGroupLabel(2))
        assertEquals("Найдено 5 групп",visualMusicGroupLabel(5))
        assertEquals("Найдено 21 группа",visualMusicGroupLabel(21))
        assertEquals("Найдено 11 групп",visualMusicGroupLabel(11))
    }
    @Test fun changingModeDoesNotStartAndPauseIsNotFailure(){
        var expanded by mutableStateOf(false);var launches=0
        compose.setContent{MaterialTheme{androidx.compose.foundation.layout.Column{
            MusicScanModeControls(expanded,{expanded=it},true,false,{launches++},true)
            MusicScanStatusCard(Outcome.PAUSED,0,null)
        }}}
        compose.onNodeWithText("Продолжить быстрый поиск").assertExists()
        compose.onNodeWithTag("duplicate-full-mode").performClick()
        compose.onNodeWithText("Запустить расширенный поиск").assertExists()
        compose.onNodeWithText("Поиск приостановлен").assertExists()
        compose.onNodeWithText("Не удалось завершить поиск").assertDoesNotExist()
        compose.runOnIdle{assertEquals(0,launches)}
        compose.onNodeWithTag("duplicate-start-mode").performClick()
        compose.runOnIdle{assertEquals(1,launches)}
    }
}

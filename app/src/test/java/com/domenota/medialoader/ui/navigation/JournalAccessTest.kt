package com.domenota.medialoader.ui.navigation

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
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
class JournalAccessTest{
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun legacyFallbackAndExplicitRevocationShareOneGate(){
        val prefs=compose.activity.getSharedPreferences("journal-test",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertFalse(JournalAccess.allowed(prefs))
        prefs.edit().putBoolean("logs_unlocked",true).commit()
        assertTrue(JournalAccess.allowed(prefs))
        JournalAccess.setAllowed(prefs,false)
        assertFalse(JournalAccess.allowed(prefs))
        assertFalse(prefs.contains("logs_unlocked"))
        JournalAccess.setAllowed(prefs,true)
        assertTrue(JournalAccess.allowed(prefs))
        prefs.edit().clear().commit()
    }
    @Test fun preferenceObserverReactsToEnableAndDisableWithoutRecreatingScreen(){
        val prefs=compose.activity.getSharedPreferences("journal-observer-test",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        compose.setContent{if(rememberJournalUnlocked(prefs))Text("Журнал")}
        compose.onNodeWithText("Журнал").assertDoesNotExist()
        compose.runOnIdle{JournalAccess.setAllowed(prefs,true)}
        compose.onNodeWithText("Журнал").assertExists()
        compose.runOnIdle{JournalAccess.setAllowed(prefs,false)}
        compose.onNodeWithText("Журнал").assertDoesNotExist()
        prefs.edit().clear().commit()
    }
}

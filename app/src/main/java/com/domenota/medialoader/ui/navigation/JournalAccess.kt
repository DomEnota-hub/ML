package com.domenota.medialoader.ui.navigation

import android.content.SharedPreferences
import androidx.compose.runtime.*

/** Single observation of the existing Settings gate, including legacy fallback.
 * No new key, unlock mechanism or independent session is introduced. */
internal object JournalAccess {
    fun allowed(prefs:SharedPreferences):Boolean =
        prefs.getBoolean("developer_visible",prefs.getBoolean("logs_unlocked",false))

    fun setAllowed(prefs:SharedPreferences,allowed:Boolean){
        prefs.edit().putBoolean("developer_visible",allowed).remove("logs_unlocked").apply()
    }
}

@Composable
internal fun rememberJournalUnlocked(prefs:SharedPreferences):Boolean{
    var unlocked by remember(prefs){mutableStateOf(JournalAccess.allowed(prefs))}
    DisposableEffect(prefs){
        val listener=SharedPreferences.OnSharedPreferenceChangeListener{_,key->
            if(key==null||key=="developer_visible"||key=="logs_unlocked")
                unlocked=JournalAccess.allowed(prefs)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        unlocked=JournalAccess.allowed(prefs)
        onDispose{prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    return unlocked
}

package com.domenota.medialoader.data.recognition

import android.content.Context
import com.domenota.medialoader.core.recognition.MusicRecognitionMode

class MusicRecognitionSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("music_recognition", Context.MODE_PRIVATE)

    var mode: MusicRecognitionMode
        get() = prefs.getString(KEY_MODE, null)
            ?.let { runCatching { MusicRecognitionMode.valueOf(it) }.getOrNull() }
            ?: MusicRecognitionMode.AUTO
        set(value) {
            prefs.edit().putString(KEY_MODE, value.name).apply()
        }

    var renameRecognized: Boolean
        get() = prefs.getBoolean(KEY_RENAME, true)
        set(value) {
            prefs.edit().putBoolean(KEY_RENAME, value).apply()
        }

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_RENAME = "rename_recognized"
    }
}

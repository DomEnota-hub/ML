package com.domenota.medialoader.data.diagnostics

import android.content.Context

/** Private, editable control inputs for full diagnostics. Never included in reports. */
class DiagnosticControlSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("diagnostic_controls", Context.MODE_PRIVATE)

    var links: String
        get() = prefs.getString("links", DEFAULT_LINKS).orEmpty()
        set(value) { prefs.edit().putString("links", value.take(2_000)).apply() }

    var artist: String
        get() = prefs.getString("artist", "Винтаж").orEmpty()
        set(value) { prefs.edit().putString("artist", value.take(120)).apply() }

    var title: String
        get() = prefs.getString("title", "Ева").orEmpty()
        set(value) { prefs.edit().putString("title", value.take(120)).apply() }

    private companion object {
        val DEFAULT_LINKS = listOf(
            "https://www.youtube.com/watch?v=lc2ob7NwXcU",
            "https://www.instagram.com/reel/DejZ9r1uR2r/?obrf=c3V3engwbW53eTBq",
            "https://rutube.ru/video/9ac5e1c7b581a9ab636423bc3d6e4845/?r=a/",
            "https://vk.com/video-190532262_456239493",
        ).joinToString("\n")
    }
}

package com.domenota.medialoader.data.library

import android.content.Context

class LibraryPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("library_modes", Context.MODE_PRIVATE)
    var mode: AppMode
        get() = AppMode.entries.firstOrNull { it.name == prefs.getString("mode", null) } ?: AppMode.DOWNLOADER
        set(value) { prefs.edit().putString("mode", value.name).apply() }
    fun source(mode: AppMode): LibrarySource = LibrarySource.entries.firstOrNull {
        it.name == prefs.getString("source_${mode.name}", null)
    } ?: LibrarySource.APP
    fun setSource(mode: AppMode, source: LibrarySource) {
        require(mode != AppMode.DOWNLOADER)
        prefs.edit().putString("source_${mode.name}", source.name).apply()
    }
}

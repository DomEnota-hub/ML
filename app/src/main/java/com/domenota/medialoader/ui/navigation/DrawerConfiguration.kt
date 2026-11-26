package com.domenota.medialoader.ui.navigation

import android.content.SharedPreferences
import androidx.compose.runtime.*

/** Stable entry names are persisted; labels and routes are not storage keys.
 * Configuration is presentation-only. Developer admission is evaluated separately. */
internal data class DrawerConfiguration private constructor(
    val order: List<DrawerEntry>,
    val visible: Set<DrawerEntry>,
) {
    fun setVisible(entry: DrawerEntry, enabled: Boolean): DrawerConfiguration =
        if (entry in mandatoryDrawerEntries || entry == DrawerEntry.DIAGNOSTICS) this
        else copy(visible = if (enabled) visible + entry else visible - entry)

    fun move(entry: DrawerEntry, target: DrawerEntry): DrawerConfiguration {
        val from = order.indexOf(entry)
        val to = order.indexOf(target)
        if (from < 0 || to < 0 || from == to) return this
        return copy(order = normalizeOrder(order.toMutableList().apply { removeAt(from); add(to, entry) }))
    }

    fun entries(developerAllowed: Boolean): List<DrawerEntry> =
        order.filter { it in visible && it in drawerAvailableEntries(developerAllowed) }

    fun encode(): String = order.joinToString(",") { it.name + ":" + if (it in visible) "1" else "0" }

    companion object {
        private val defaultVisible = setOf(
            DrawerEntry.HOME, DrawerEntry.DOWNLOADS, DrawerEntry.GALLERY, DrawerEntry.MUSIC,
            DrawerEntry.MUSIC_SEARCH, DrawerEntry.FAVORITES, DrawerEntry.PRIVATE, DrawerEntry.ARCHIVE,
            DrawerEntry.COLLECTIONS, DrawerEntry.TRASH, DrawerEntry.SETTINGS, DrawerEntry.APPEARANCE,
            DrawerEntry.JOURNAL, DrawerEntry.TASK_CENTER,
        )
        private fun normalizeOrder(order:List<DrawerEntry>):List<DrawerEntry> {
            val task=order.indexOf(DrawerEntry.TASK_CENTER)
            val service=order.indexOfFirst { it == DrawerEntry.SETTINGS || it == DrawerEntry.JOURNAL }
            if(task<0 || service<0 || task<service)return order
            return order.toMutableList().apply { removeAt(task); add(service,DrawerEntry.TASK_CENTER) }
        }
        val DEFAULT = DrawerConfiguration(DrawerEntry.entries.toList(), defaultVisible)
        const val PREF = "drawer_configuration_v1"

        fun decode(raw: String?): DrawerConfiguration {
            if (raw.isNullOrBlank()) return DEFAULT
            val stored = linkedMapOf<DrawerEntry, Boolean>()
            raw.split(',').forEach { record ->
                val parts = record.split(':')
                if (parts.size == 2 && parts[1] in setOf("0", "1")) {
                    val entry = DrawerEntry.entries.firstOrNull { it.name == parts[0] }
                    if (entry != null && entry !in stored) stored[entry] = parts[1] == "1"
                }
            }
            if (stored.isEmpty()) return DEFAULT
            val missing = DrawerEntry.entries.filterNot { it in stored }
            return DrawerConfiguration(
                normalizeOrder(stored.keys.toList() + missing),
                (stored.filterValues { it }.keys + missing.filter { it in defaultVisible } + mandatoryDrawerEntries) - DrawerEntry.DIAGNOSTICS,
            )
        }

        fun read(prefs: SharedPreferences): DrawerConfiguration =
            decode(runCatching { prefs.getString(PREF, null) }.getOrNull())

        fun write(prefs: SharedPreferences, value: DrawerConfiguration) {
            prefs.edit().putString(PREF, value.encode()).apply()
        }
    }
}

@Composable
internal fun rememberDrawerConfiguration(prefs: SharedPreferences): DrawerConfiguration {
    var value by remember(prefs) { mutableStateOf(DrawerConfiguration.read(prefs)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == DrawerConfiguration.PREF) value = DrawerConfiguration.read(prefs)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        value = DrawerConfiguration.read(prefs)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}

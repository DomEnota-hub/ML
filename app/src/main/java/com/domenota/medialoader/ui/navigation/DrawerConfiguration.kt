package com.domenota.medialoader.ui.navigation

import android.content.SharedPreferences
import androidx.compose.runtime.*

/** Stable entry names are persisted; labels and routes are not storage keys.
 * Configuration is presentation-only. Developer admission is evaluated separately. */
internal data class DrawerConfiguration private constructor(
    val layout: List<String>,
    val visible: Set<DrawerEntry>,
) {
    val order: List<DrawerEntry> get() = layout.mapNotNull(::entry)
    fun setVisible(entry: DrawerEntry, enabled: Boolean): DrawerConfiguration =
        if (entry in mandatoryDrawerEntries || entry == DrawerEntry.DIAGNOSTICS) this
        else copy(visible = if (enabled) visible + entry else visible - entry)

    fun move(entry: DrawerEntry, target: DrawerEntry): DrawerConfiguration = moveItem(entry.name, target.name)
    fun moveItem(item: String, target: String): DrawerConfiguration {
        val from = layout.indexOf(item)
        val to = layout.indexOf(target)
        if (from < 0 || to < 0 || from == to) return this
        return copy(layout = normalizeLayout(layout.toMutableList().apply { removeAt(from); add(to, item) }))
    }
    fun addDivider(): DrawerConfiguration {
        val id = (1..MAX_DIVIDERS).map { "DIVIDER_" + it }.firstOrNull { it !in layout } ?: return this
        val at = layout.indexOf(DrawerEntry.TASK_CENTER.name).takeIf { it >= 0 } ?: layout.size
        return copy(layout = layout.toMutableList().apply { add(at,id) })
    }
    fun removeDivider(id: String): DrawerConfiguration =
        if (isDivider(id)) copy(layout = layout - id) else this
    fun editableItems(admitted: Set<DrawerEntry>): List<String> =
        layout.filter { isDivider(it) || entry(it) in admitted }
    /** Remove leading, trailing and adjacent separators only after visibility/admission filtering. */
    fun displayItems(admitted: Set<DrawerEntry>): List<String> {
        val result = mutableListOf<String>()
        var pending: String? = null
        for (id in layout) {
            if (isDivider(id)) {
                if (result.isNotEmpty() && pending == null) pending = id
            } else if (entry(id) in admitted && entry(id) in visible) {
                pending?.let(result::add)
                pending = null
                result.add(id)
            }
        }
        return result
    }
    fun entries(developerAllowed: Boolean, advancedToolsVisible:Boolean=false): List<DrawerEntry> =
        order.filter { it in visible && it in drawerAvailableEntries(developerAllowed,advancedToolsVisible) }

    // v2 marker distinguishes a deliberate empty set of separators from a legacy config.
    fun encode(): String = "v2," + layout.joinToString(",") {
        if (isDivider(it)) it + ":1" else it + ":" + if (entry(it) in visible) "1" else "0"
    }

    companion object {
        private const val MAX_DIVIDERS = 20
        fun entry(id: String): DrawerEntry? = DrawerEntry.entries.firstOrNull { it.name == id }
        fun isDivider(id: String): Boolean = id.startsWith("DIVIDER_") &&
            id.substringAfter("DIVIDER_").toIntOrNull()?.let { it in 1..MAX_DIVIDERS && id == "DIVIDER_" + it } == true
        private val defaultVisible = setOf(
            DrawerEntry.HOME, DrawerEntry.DOWNLOADS, DrawerEntry.GALLERY, DrawerEntry.MUSIC,
            DrawerEntry.MUSIC_SEARCH, DrawerEntry.FAVORITES, DrawerEntry.PRIVATE, DrawerEntry.ARCHIVE,
            DrawerEntry.COLLECTIONS, DrawerEntry.TRASH, DrawerEntry.SETTINGS, DrawerEntry.APPEARANCE,
            DrawerEntry.JOURNAL, DrawerEntry.TASK_CENTER,
        )
        private fun normalizeLayout(layout:List<String>):List<String> {
            val order = layout.mapNotNull(::entry).toMutableList()
            val task=order.indexOf(DrawerEntry.TASK_CENTER)
            val service=order.indexOfFirst { it == DrawerEntry.SETTINGS || it == DrawerEntry.JOURNAL }
            if(task<0 || service<0 || task<service)return layout
            order.removeAt(task); order.add(service,DrawerEntry.TASK_CENTER)
            val iter=order.iterator()
            return layout.map { if(isDivider(it)) it else iter.next().name }
        }
        private fun defaultLayout():List<String> = buildList {
            DrawerEntry.entries.forEach {
                add(it.name)
                if(it==DrawerEntry.MUSIC_SEARCH)add("DIVIDER_1")
                if(it==DrawerEntry.TRASH)add("DIVIDER_2")
            }
        }
        val DEFAULT = DrawerConfiguration(defaultLayout(), defaultVisible)
        const val PREF = "drawer_configuration_v1"

        fun decode(raw: String?): DrawerConfiguration {
            if (raw.isNullOrBlank()) return DEFAULT
            val stored = linkedMapOf<DrawerEntry, Boolean>()
            val layout = mutableListOf<String>()
            val v2 = raw.startsWith("v2,")
            raw.split(',').forEach { record ->
                val parts = record.split(':')
                if (parts.size == 2 && parts[1] in setOf("0", "1")) {
                    val id=parts[0]
                    val item = entry(id)
                    if (item != null && item !in stored) {
                        stored[item] = parts[1] == "1"
                        layout.add(id)
                    } else if(v2 && isDivider(id) && id !in layout) layout.add(id)
                }
            }
            if (stored.isEmpty()) return DEFAULT
            val missing = DrawerEntry.entries.filterNot { it in stored }
            val normalized = normalizeLayout(layout + missing.map { it.name })
            // Never rearrange customized legacy entries merely to insert new dividers.
            val migrated = if(!v2 && normalized == DrawerEntry.entries.map { it.name }) defaultLayout() else normalized
            return DrawerConfiguration(
                migrated,
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

/** Feature admission does not mutate per-entry visibility or order. */
@Composable
internal fun rememberAdvancedDrawerTools(prefs:SharedPreferences):Boolean {
    var enabled by remember(prefs){mutableStateOf(prefs.getBoolean("bulk_visible",false))}
    DisposableEffect(prefs){
        val listener=SharedPreferences.OnSharedPreferenceChangeListener{_,key->
            if(key==null||key=="bulk_visible")enabled=prefs.getBoolean("bulk_visible",false)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        enabled=prefs.getBoolean("bulk_visible",false)
        onDispose{prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    return enabled
}

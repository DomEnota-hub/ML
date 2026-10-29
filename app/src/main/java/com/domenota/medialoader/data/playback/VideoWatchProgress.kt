package com.domenota.medialoader.data.playback

import android.content.SharedPreferences
import org.json.JSONObject
import java.util.WeakHashMap

/** Watch marks are separate from resume positions so a completed video can still show 100%. */
internal object VideoWatchProgress {
    private const val LINKS = "identity_aliases_v1"
    private data class CachedAliases(val json: String?, val values: Map<String, String>)
    private val cache = WeakHashMap<SharedPreferences, CachedAliases>()
    @Synchronized private fun aliases(prefs: SharedPreferences): Map<String, String> {
        val json = prefs.getString(LINKS, null)
        cache[prefs]?.takeIf { it.json == json }?.let { return it.values }
        val values = runCatching {
            val stored = JSONObject(json ?: "{}")
            stored.keys().asSequence().associateWith { stored.getString(it) }
        }.getOrDefault(emptyMap())
        cache[prefs] = CachedAliases(json, values)
        return values
    }
    private fun key(prefs: SharedPreferences, id: String) = aliases(prefs)[id] ?: id

    /** Preferences survive history-only cleanup. Link current physical aliases to one durable
     * preferences key, without copying/overwriting a playing video's position. */
    fun linkAliases(prefs: SharedPreferences, current: Map<String, String>) = synchronized(prefs) {
        val existing = aliases(prefs)
        val linked = existing.toMutableMap()
        current.entries.groupBy({ it.value }, { it.key }).forEach { (canonical, group) ->
            val known = group.toSet() + canonical
            val previous = known.mapNotNullTo(mutableSetOf()) { linked[it] }
            val candidates = known + previous
            val stable = candidates.maxWithOrNull(compareBy<String>(
                { prefs.getLong("updated_$it", 0L) },
                { maxOf(prefs.getLong(it, 0L), prefs.getLong("watched_$it", 0L)) },
                { it in previous }, { it == canonical }, { it })) ?: canonical
            if (previous.any { it != stable }) linked.replaceAll { _, value -> if (value in previous) stable else value }
            known.forEach { linked[it] = stable }
        }
        if (linked != existing && !prefs.edit().putString(LINKS, JSONObject(linked as Map<*, *>).toString()).commit())
            throw java.io.IOException("Could not save video identity links")
    }

    fun resumePosition(prefs: SharedPreferences, id: String, fallback: Long = 0L): Long = synchronized(prefs) {
        prefs.getLong(key(prefs, id), fallback).coerceAtLeast(0L)
    }
    fun saveResume(prefs: SharedPreferences, id: String, positionMs: Long?) = synchronized(prefs) {
        val stable = key(prefs, id)
        val edit = prefs.edit().putLong("updated_$stable", System.currentTimeMillis())
        if (positionMs == null) edit.remove(stable) else edit.putLong(stable, positionMs.coerceAtLeast(0L))
        edit.apply()
    }
    fun read(prefs: SharedPreferences, id: String, knownDurationMs: Long?): Float = synchronized(prefs) {
        val stable = key(prefs, id)
        val duration = prefs.getLong("duration_$stable", 0L).takeIf { it > 0L } ?: knownDurationMs ?: 0L
        if (duration <= 0L) return@synchronized 0f
        val position = prefs.getLong("watched_$stable", prefs.getLong(stable, 0L))
        (position.toDouble() / duration).coerceIn(0.0, 1.0).toFloat()
    }

    fun record(prefs: SharedPreferences, id: String, positionMs: Long, durationMs: Long) = synchronized(prefs) {
        if (durationMs <= 0L) return@synchronized
        val stable = key(prefs, id)
        prefs.edit().putLong("watched_$stable", positionMs.coerceIn(0L, durationMs))
            .putLong("duration_$stable", durationMs).putLong("updated_$stable", System.currentTimeMillis()).apply()
    }
}

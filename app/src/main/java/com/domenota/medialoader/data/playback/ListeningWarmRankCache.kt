package com.domenota.medialoader.data.playback

/**
 * Last validated visible "Часто слушаю" result, retained while the app process
 * lives. The already existing SQLite projection_cache is the persistent layer.
 *
 * Guard contains the current public-audio/privacy/permission identity. A closed
 * or changed guard cannot access old results, even briefly during recomposition.
 * Only track identifiers and playback counts are retained, never media bytes/URIs.
 */
internal data class ListeningWarmRankKey(
    val guard: Any,
    val eligibleDigest: String,
    val period: ListeningPeriod,
    val visibilityRevision: Long,
    val zone: String,
)

internal class ListeningWarmRankCache {
    private var stored: Pair<ListeningWarmRankKey, ListeningSelection>? = null

    @Synchronized fun get(key: ListeningWarmRankKey?, now: Long): ListeningSelection? {
        if(key == null) return null
        val current = stored ?: return null
        return current.second.takeIf { current.first == key && it.currentAt(now) }
    }

    @Synchronized fun put(key: ListeningWarmRankKey, validated: ListeningSelection, now: Long) {
        if(!validated.currentAt(now)) return
        stored = key to validated
    }

    @Synchronized fun clear() { stored = null }
}

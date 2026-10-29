package com.domenota.medialoader.data.library

import android.content.Context
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Membership references only. Unavailable tracks stay in the list until explicitly removed. */
data class MediaPlaylist(val id: String, val name: String, val trackIds: List<String>,
    val trackReferences: Map<String, Set<String>> = emptyMap())

class PlaylistRepository(context: Context) {
    private val context = context.applicationContext
    private val state = MutableStateFlow<List<MediaPlaylist>>(emptyList())
    val playlists: StateFlow<List<MediaPlaylist>> = state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loaded = scope.async { storageMutex.withLock { state.value = read() } }
    private val preferences get() = context.getSharedPreferences("media_playlists", Context.MODE_PRIVATE)

    suspend fun awaitLoaded() { loaded.await() }

    suspend fun create(name: String): String {
        val validName = validatedName(name)
        val id = UUID.randomUUID().toString()
        mutate { it + MediaPlaylist(id, validName, emptyList()) }
        return id
    }

    suspend fun rename(id: String, name: String) {
        val validName = validatedName(name)
        update(id) { it.copy(name = validName) }
    }

    suspend fun delete(id: String) { mutate { lists -> lists.filterNot { it.id == id } } }

    suspend fun addTracks(id: String, trackIds: List<String>) {
        require(trackIds.all { it.isNotBlank() }) { "Track IDs cannot be blank" }
        update(id) { it.copy(trackIds = (it.trackIds + trackIds).distinct()) }
    }

    suspend fun removeTrack(id: String, trackId: String) {
        require(trackId.isNotBlank()) { "Track ID cannot be blank" }
        update(id) { it.copy(trackIds = it.trackIds.filterNot { candidate -> candidate == trackId }, trackReferences = it.trackReferences - trackId) }
    }

    suspend fun addTracksResolved(id: String, trackIds: List<String>, aliases: Map<String, String>) {
        require(trackIds.all { it.isNotBlank() }) { "Track IDs cannot be blank" }
        update(id) { playlist -> resolveReferences(playlist, aliases, trackIds) }
    }

    suspend fun reconcileAliases(aliases: Map<String, String>) {
        if (aliases.isEmpty()) return
        val references = aliases.entries.groupBy({ it.value }, { it.key })
        mutate { lists -> lists.map { playlist -> resolveReferences(playlist, aliases, referenceIndex = references) } }
    }

    /** Canonical IDs are UI keys, not replacements for the original file references. Keep
     * alternatives in the same atomic playlist document so history-only cleanup is harmless. */
    private fun resolveReferences(playlist: MediaPlaylist, aliases: Map<String, String>, added: List<String> = emptyList(),
        referenceIndex: Map<String, List<String>> = aliases.entries.groupBy({ it.value }, { it.key })): MediaPlaylist {
        val tracks = linkedSetOf<String>()
        val references = mutableMapOf<String, Set<String>>()
        (playlist.trackIds + added).forEach { id ->
            val previous = playlist.trackReferences[id].orEmpty() + id
            val resolved = aliases[id] ?: previous.firstNotNullOfOrNull { aliases[it] } ?: id
            tracks += resolved
            val known = previous + referenceIndex[resolved].orEmpty() + resolved + references[resolved].orEmpty()
            if (known.size > 1) references[resolved] = known
        }
        return playlist.copy(trackIds = tracks.toList(), trackReferences = references)
    }

    suspend fun moveTrack(id: String, trackId: String, delta: Int) {
        require(trackId.isNotBlank()) { "Track ID cannot be blank" }
        update(id) { playlist ->
            val tracks = playlist.trackIds.toMutableList()
            val index = tracks.indexOf(trackId)
            if (index < 0) playlist else {
                val target = (index.toLong() + delta.toLong()).coerceIn(0L, tracks.lastIndex.toLong()).toInt()
                tracks.add(target, tracks.removeAt(index))
                playlist.copy(trackIds = tracks)
            }
        }
    }

    private suspend fun update(id: String, operation: (MediaPlaylist) -> MediaPlaylist) {
        require(id.isNotBlank()) { "Playlist ID cannot be blank" }
        mutate { lists ->
            require(lists.any { it.id == id }) { "Playlist no longer exists" }
            lists.map { if (it.id == id) operation(it) else it }
        }
    }

    private suspend fun mutate(operation: (List<MediaPlaylist>) -> List<MediaPlaylist>) = withContext(Dispatchers.IO) {
        loaded.await()
        storageMutex.withLock {
            val current = read()
            val updated = operation(current)
            if (updated == current) { state.value = updated; return@withLock }
            val json = JSONArray()
            updated.forEach { playlist ->
                val references = JSONObject()
                playlist.trackReferences.forEach { (id, aliases) -> references.put(id, JSONArray(aliases.toList())) }
                json.put(JSONObject().put("id", playlist.id).put("name", playlist.name).put("trackIds", JSONArray(playlist.trackIds))
                    .put("trackReferences", references))
            }
            if (!preferences.edit().putString("playlists", json.toString()).commit()) throw IOException("Could not save playlists")
            state.value = updated
        }
    }

    private fun read(): List<MediaPlaylist> {
        val json = JSONArray(preferences.getString("playlists", "[]") ?: "[]")
        return (0 until json.length()).map { index ->
            val item = json.getJSONObject(index)
            val tracks = item.getJSONArray("trackIds")
            val references = item.optJSONObject("trackReferences")
            val known = references?.let { stored -> stored.keys().asSequence().associateWith { id ->
                val values = stored.getJSONArray(id)
                (0 until values.length()).map { values.getString(it) }.filter { it.isNotBlank() }.toSet()
            } }.orEmpty()
            MediaPlaylist(item.getString("id"), validatedName(item.getString("name")),
                (0 until tracks.length()).map { tracks.getString(it) }.filter { it.isNotBlank() }.distinct(), known)
        }.distinctBy { it.id }
    }

    private fun validatedName(name: String): String = name.trim().also { require(it.isNotEmpty()) { "Playlist name cannot be blank" } }

    companion object {
        // Serializes independent repository instances as well as concurrent UI operations.
        private val storageMutex = Mutex()
    }
}

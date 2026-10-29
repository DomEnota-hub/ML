package com.domenota.medialoader.data.library

import android.content.Context
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class ExternalMediaEntry(val ref: LibraryMediaRef, val folderId: String?, val isFavorite: Boolean)

/** Logical metadata only. A stored reference never proves its original is still accessible. */
class ExternalMediaCatalogRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("external_media_catalog", Context.MODE_PRIVATE)
    private val state = MutableStateFlow<List<ExternalMediaEntry>>(emptyList())
    val entries = state.asStateFlow()
    private val loaded = CoroutineScope(SupervisorJob() + Dispatchers.IO).async { storageMutex.withLock { state.value = read() } }

    suspend fun setFavorite(ref: LibraryMediaRef, favorite: Boolean) = update(ref) { it.copy(isFavorite = favorite) }
    suspend fun setFolder(ref: LibraryMediaRef, folderId: String?) {
        require(folderId == null || folderId.isNotBlank()) { "Папка не найдена" }
        update(ref) { it.copy(folderId = folderId) }
    }
    suspend fun removeFolder(folderId: String) = mutate { rows ->
        rows.map { if (it.folderId == folderId) it.copy(folderId = null) else it }.filter { it.isFavorite || it.folderId != null }
    }
    suspend fun setExistingFavorite(ref: LibraryMediaRef, favorite: Boolean) = updateExisting(ref) { it.copy(isFavorite = favorite) }
    suspend fun setExistingFolder(ref: LibraryMediaRef, folderId: String?) = updateExisting(ref) { it.copy(folderId = folderId) }
    private suspend fun updateExisting(ref: LibraryMediaRef, operation: (ExternalMediaEntry) -> ExternalMediaEntry) {
        require(!ref.item.isPrivate && !ref.item.hidden) { "Файл недоступен" }
        val aliases = ref.aliases + ref.key
        mutate { rows -> rows.map { entry ->
            if (entry.ref.key in aliases || entry.ref.aliases.any(aliases::contains)) operation(entry) else entry
        }.filter { it.isFavorite || it.folderId != null } }
    }
    private suspend fun update(ref: LibraryMediaRef, operation: (ExternalMediaEntry) -> ExternalMediaEntry) {
        require(ref.appDownloadId == null && !ref.item.isPrivate && !ref.item.hidden &&
            ref.item.state == DownloadState.COMPLETED && !ref.item.savedUri.isNullOrBlank()) { "Файл недоступен" }
        mutate { rows ->
            val identities = ref.aliases + ref.key + ref.item.id
            val old = rows.firstOrNull { it.ref.key in identities || it.ref.aliases.any(identities::contains) }
            val key = old?.ref?.key ?: ref.key
            val stable = ref.copy(key = key, aliases = identities + old?.ref?.aliases.orEmpty(), item = ref.item.copy(id = key))
            val changed = operation(ExternalMediaEntry(stable, old?.folderId, old?.isFavorite == true))
            (rows.filterNot { it === old } + changed).filter { it.isFavorite || it.folderId != null }
        }
    }
    private suspend fun mutate(operation: (List<ExternalMediaEntry>) -> List<ExternalMediaEntry>) = withContext(Dispatchers.IO) {
        loaded.await()
        storageMutex.withLock {
            val current = read()
            val updated = operation(current)
            if (updated != current && !preferences.edit().putString("catalog", encode(updated)).commit())
                throw IOException("Не удалось сохранить сведения о файле")
            state.value = updated
        }
    }
    private fun encode(rows: List<ExternalMediaEntry>): String {
        val entries = JSONArray()
        rows.forEach { entry ->
            val ref = entry.ref
            val item = ref.item
            entries.put(JSONObject().put("key", ref.key).put("aliases", JSONArray(ref.aliases.toList()))
                .put("folder", entry.folderId ?: JSONObject.NULL).put("favorite", entry.isFavorite)
                .put("uri", item.savedUri).put("name", item.originalName).put("type", item.mediaType.name)
                .put("date", item.createdAtEpochMillis).put("size", item.sizeBytes ?: JSONObject.NULL)
                .put("duration", item.durationMs ?: JSONObject.NULL).put("album", item.groupId ?: JSONObject.NULL)
                .put("artist", item.sourceAudioArtist ?: JSONObject.NULL).put("title", item.sourceAudioTitle ?: JSONObject.NULL))
        }
        return JSONObject().put("version", 1).put("entries", entries).toString()
    }
    private fun read(): List<ExternalMediaEntry> {
        val raw = preferences.getString("catalog", null) ?: return emptyList()
        val root = JSONObject(raw)
        require(root.getInt("version") == 1) { "Неизвестная версия каталога файлов" }
        val rows = root.getJSONArray("entries")
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            fun nullable(name: String) = if (row.isNull(name)) null else row.getString(name)
            val key = row.getString("key")
            val type = MediaType.valueOf(row.getString("type"))
            val aliases = row.getJSONArray("aliases")
            val uri = row.getString("uri")
            ExternalMediaEntry(LibraryMediaRef(key, (0 until aliases.length()).mapTo(mutableSetOf(key)) { aliases.getString(it) },
                DownloadEntity(key, "device", row.getString("name"), type, DownloadState.COMPLETED, uri,
                    if (row.isNull("size")) null else row.getLong("size"), row.getLong("date"),
                    previewUrl = uri.takeIf { type != MediaType.AUDIO }, groupId = nullable("album"),
                    sourceAudioTitle = nullable("title"), sourceAudioArtist = nullable("artist"),
                    durationMs = if (row.isNull("duration")) null else row.getLong("duration")), null),
                nullable("folder"), row.getBoolean("favorite"))
        }
    }
    companion object { private val storageMutex = Mutex() }
}

package com.domenota.medialoader.data.storage

import android.content.Context
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.AtomicFile
import androidx.room.withTransaction
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.database.HistoryDatabase
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.data.library.ExternalMediaCatalogRepository
import com.domenota.medialoader.data.library.PlaylistRepository
import com.domenota.medialoader.data.playback.PublicMediaIdentity
import com.domenota.medialoader.data.playback.VideoWatchProgress
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Cross-store intent is persisted BEFORE a CAS, making a committed-but-unacknowledged step replayable. */
internal class HistoricalReferenceLinks(private val context: Context, private val db: HistoryDatabase) {
    private val catalog = ExternalMediaCatalogRepository(context)
    private val playlists = PlaylistRepository(context)
    private val directory = File(context.noBackupFilesDir, "historical-reference-links-v1")
    private val vkFile get() = context.getDatabasePath("vk-community-images.db")
    private val watch get() = context.getSharedPreferences("video_playback_positions", Context.MODE_PRIVATE)

    suspend fun ensureIdle() {
        check(db.downloadDao().all().none { it.state in setOf(DownloadState.RUNNING, DownloadState.QUEUED) }) {
            "Дождитесь завершения загрузок и очереди"
        }
        readVk { vk -> vk.rawQuery("SELECT COUNT(*) FROM jobs WHERE phase IN ('SCANNING','DOWNLOADING')", null).use {
            check(it.moveToFirst() && it.getInt(0) == 0) { "Сначала завершите поиск и загрузку из сообществ VK" }
        } }
    }

    suspend fun snapshot(row: DownloadEntity, originalAliases: Set<String> = emptySet()): JSONObject {
        ensureIdle()
        val old = requireNotNull(row.savedUri)
        val aliases = aliases(old) + row.id + originalAliases
        val others = db.downloadDao().all().filter { it.id != row.id }
        check(others.none { other -> listOfNotNull(other.savedUri, other.privateSourceUri).any { uri ->
            (PublicMediaIdentity.uriKeys(uri) intersect aliases).isNotEmpty()
        } }) { "Физический файл связан с несколькими записями ML" }
        check(db.downloadDao().countSavedUriReferences(old) == 1) { "Неоднозначные ссылки исходного файла" }
        check((others + row).none { it.sourceUrl == old || it.sourcePageUrl == old }) { "Исходный URI используется как адрес источника" }
        val vk = JSONObject().put("images", JSONArray()).put("hashes", JSONArray())
        readVk { handle -> for (table in listOf("images", "hashes")) {
            vk.put(table, rows(handle, "SELECT * FROM $table WHERE saved_uri=?", arrayOf(old)))
        } }
        check(vk.getJSONArray("hashes").length() <= 1) { "Несколько разных хешей связаны с одним исходным файлом VK" }
        val assignment = db.catalogDao().assignmentById(row.id)
        val artwork = JSONArray()
        (others + row).forEach { item ->
            if (listOf(item.previewUrl, item.sourceAudioArtworkUrl, item.recognitionArtworkUrl).contains(old)) {
                artwork.put(JSONObject().put("id", item.id).put("previewUrl", item.previewUrl ?: JSONObject.NULL)
                    .put("sourceAudioArtworkUrl", item.sourceAudioArtworkUrl ?: JSONObject.NULL)
                    .put("recognitionArtworkUrl", item.recognitionArtworkUrl ?: JSONObject.NULL))
            }
        }
        val result = JSONObject().put("version", 1).put("key", UUID.randomUUID().toString())
            .put("id", row.id).put("oldUri", old).put("aliases", JSONArray(aliases.toList().sorted()))
            .put("catalog", catalog.snapshotHistoricalMedia()).put("playlists", playlists.snapshotHistoricalMedia())
            .put("vk", vk).put("artwork", artwork)
            .put("assignment", if (assignment == null) JSONObject.NULL else JSONObject()
                .put("folder", assignment.folderId ?: JSONObject.NULL).put("favorite", assignment.isFavorite))
            .put("watchBackup", JSONObject(watch.all))
        // Validate the exact transformations BEFORE any original is renamed or
        // published, rather than discovering ambiguous catalog entries at rebind.
        catalogTarget(result.getString("catalog"), aliases, old, old, row.originalName, row.sizeBytes!!)
        playlistTarget(result.getString("playlists"), aliases)
        return result
    }

    suspend fun rebind(snapshot: JSONObject, oldUri: String, newUri: String, oldHash: String,
        newHash: String, newName: String, newBytes: Long) {
        val key = snapshot.getString("key")
        val recordFile = File(directory, "$key.json")
        if (!recordFile.exists()) write(key, snapshot)
        val record = read(key)
        check(record.getInt("version") == 1 && record.getString("oldUri") == oldUri)
        if (!record.has("newUri")) {
            val oldAliases = strings(record.getJSONArray("aliases"))
            val allAliases = oldAliases + aliases(newUri) + newUri + oldUri + record.getString("id")
            record.put("newUri", newUri).put("newHash", newHash).put("oldHash", oldHash)
                .put("allAliases", JSONArray(allAliases.toList().sorted()))
                .put("catalogTarget", catalogTarget(record.getString("catalog"), allAliases, oldUri, newUri, newName, newBytes))
                .put("playlistsTarget", playlistTarget(record.getString("playlists"), allAliases))
            write(key, record) // complete expected+desired documents before first preference commit
        }
        check(record.getString("newUri") == newUri && record.getString("newHash") == newHash) { "Назначение перепривязки изменилось" }
        val currentCatalog = catalog.snapshotHistoricalMedia()
        val desiredCatalog = record.getString("catalogTarget")
        if (currentCatalog != desiredCatalog) catalog.restoreHistoricalMedia(record.getString("catalog"), desiredCatalog)
        val currentPlaylists = playlists.snapshotHistoricalMedia()
        val desiredPlaylists = record.getString("playlistsTarget")
        if (currentPlaylists != desiredPlaylists) playlists.restoreHistoricalMedia(record.getString("playlists"), desiredPlaylists)
        switchVk(record)
        switchArtwork(record)
        val all = strings(record.getJSONArray("allAliases"))
        VideoWatchProgress.linkAliases(watch, all.associateWith { record.getString("id") })
        verify(snapshot, newUri, newHash)
    }

    suspend fun verify(snapshot: JSONObject, expectedUri: String, expectedHash: String) {
        val record = read(snapshot.getString("key"))
        check(record.getString("newUri") == expectedUri && record.getString("newHash") == expectedHash)
        check(catalog.snapshotHistoricalMedia() == record.getString("catalogTarget")) { "Каталог не подтвердил перепривязку" }
        check(playlists.snapshotHistoricalMedia() == record.getString("playlistsTarget")) { "Плейлисты не подтвердили перепривязку" }
        val id = record.getString("id")
        val originalAssignment = record.optJSONObject("assignment")
        val assignment = db.catalogDao().assignmentById(id)
        check(if (originalAssignment == null) assignment == null else assignment != null &&
            assignment.folderId == nullable(originalAssignment, "folder") && assignment.isFavorite == originalAssignment.getBoolean("favorite")) {
            "Изменилось избранное или альбом загрузки"
        }
        check(db.downloadDao().byId(id)?.savedUri == expectedUri) { "История загрузок не открывает новый URI" }
        check(db.downloadDao().countSavedUriReferences(record.getString("oldUri")) == 0) { "Остались ссылки на старый URI" }
        readVk { handle ->
            for (table in listOf("images", "hashes")) {
                val oldCount = rows(handle, "SELECT * FROM $table WHERE saved_uri=?", arrayOf(record.getString("oldUri"))).length()
                check(oldCount == 0) { "Индекс VK всё ещё использует оригинал" }
                val baseline = record.getJSONObject("vk").getJSONArray(table)
                for (i in 0 until baseline.length()) {
                    val desired = vkTarget(baseline.getJSONObject(i), expectedUri, expectedHash)
                    val found = vkRow(handle, table, desired)
                    check(found != null && sameJson(found, desired)) { "Строка VK не сохранила связи" }
                }
            }
            val hashes = record.getJSONObject("vk").getJSONArray("hashes")
            if (hashes.length() > 0) {
                val physical = vkTarget(hashes.getJSONObject(0), expectedUri, expectedHash).put("sha256", expectedHash)
                val found = vkRow(handle, "hashes", physical)
                check(found != null && sameJson(found, physical)) { "Не сохранён новый SHA-256 VK" }
            }
        }
        val all = strings(record.getJSONArray("allAliases"))
        val linked = JSONObject(watch.getString("identity_aliases_v1", "{}")!!)
        val stable = linked.getString(id)
        check(all.all { linked.optString(it) == stable }) { "Не сохранена идентичность позиции видео" }
        verifyArtwork(record)
    }

    private suspend fun aliases(uri: String): Set<String> {
        val parsed = Uri.parse(uri)
        val kind = if (parsed.pathSegments.contains("images")) "image" else "video"
        val id = parsed.lastPathSegment?.toLongOrNull() ?: error("Неизвестный MediaStore ID")
        return PublicMediaIdentity.capture(context, uri) + "phone:$kind:$id" + uri
    }
    private fun catalogTarget(raw: String, aliases: Set<String>, old: String, new: String, name: String, bytes: Long): String {
        if (raw.isEmpty()) return raw
        val root = JSONObject(raw); check(root.getInt("version") == 1)
        val entries = root.getJSONArray("entries"); var matched = 0
        for (i in 0 until entries.length()) {
            val row = entries.getJSONObject(i)
            if ((strings(row.getJSONArray("aliases")) + row.getString("key")).any(aliases::contains) || row.getString("uri") == old) {
                check(++matched == 1 && row.getString("uri") in setOf(old, new)) { "Неоднозначная идентичность каталога" }
                row.put("uri", new).put("name", name).put("size", bytes)
                    .put("aliases", JSONArray((strings(row.getJSONArray("aliases")) + aliases).toList().sorted()))
            }
        }
        return root.toString()
    }
    private fun playlistTarget(raw: String, aliases: Set<String>): String {
        if (raw.isEmpty()) return raw
        val lists = JSONArray(raw)
        for (i in 0 until lists.length()) {
            val list = lists.getJSONObject(i); val ids = strings(list.getJSONArray("trackIds"))
            val references = list.optJSONObject("trackReferences") ?: JSONObject().also { list.put("trackReferences", it) }
            for (id in ids) {
                val known = references.optJSONArray(id)?.let(::strings).orEmpty() + id
                if (known.any(aliases::contains)) references.put(id, JSONArray((known + aliases).toList().sorted()))
            }
        }
        return lists.toString()
    }
    private suspend fun switchArtwork(record: JSONObject) = db.withTransaction {
        val rows = record.getJSONArray("artwork")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i); val id = row.getString("id")
            val current = db.downloadDao().byId(id) ?: error("Исчезла связанная запись превью")
            val names = listOf("previewUrl", "sourceAudioArtworkUrl", "recognitionArtworkUrl")
            val actual = listOf(current.previewUrl, current.sourceAudioArtworkUrl, current.recognitionArtworkUrl)
            val before = names.map { nullable(row, it) }
            val after = before.map { if (it == record.getString("oldUri")) record.getString("newUri") else it }
            check(actual == before || actual == after) { "Связанные превью изменились" }
            if (actual != after) db.openHelper.writableDatabase.execSQL(
                "UPDATE downloads SET previewUrl=?,sourceAudioArtworkUrl=?,recognitionArtworkUrl=? WHERE id=?",
                arrayOf(after[0], after[1], after[2], id))
        }
    }
    private suspend fun verifyArtwork(record: JSONObject) {
        val old = record.getString("oldUri")
        check(db.downloadDao().all().none { listOf(it.previewUrl, it.sourceAudioArtworkUrl, it.recognitionArtworkUrl).contains(old) }) {
            "Остались локальные превью старого файла"
        }
    }
    private fun switchVk(record: JSONObject) {
        if (!vkFile.exists()) {
            check(record.getJSONObject("vk").getJSONArray("images").length() == 0 && record.getJSONObject("vk").getJSONArray("hashes").length() == 0)
            return
        }
        SQLiteDatabase.openDatabase(vkFile.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { handle ->
            handle.beginTransaction()
            try {
                for (table in listOf("images", "hashes")) {
                    val baseline = record.getJSONObject("vk").getJSONArray(table)
                    for (i in 0 until baseline.length()) {
                        val before = baseline.getJSONObject(i)
                        val after = vkTarget(before, record.getString("newUri"), record.getString("newHash"))
                        val actual = vkRow(handle, table, before) ?: vkRow(handle, table, after)
                        check(actual != null && (sameJson(actual, before) || sameJson(actual, after))) { "Индекс VK изменился во время замены" }
                        if (sameJson(actual, after)) continue
                        val where = if (table == "images") "job_id=? AND photo_key=?" else "sha256=?"
                        val args = if (table == "images") arrayOf(before.getString("job_id"), before.getString("photo_key")) else arrayOf(before.getString("sha256"))
                        val values = ContentValues().apply { put("saved_uri", after.getString("saved_uri")) }
                        check(handle.update(table, values, where, args) == 1)
                    }
                }
                // The source SHA identifies future incoming downloads. Keep it as an alias;
                // add the dated container SHA without replacing somebody else's dedup record.
                val hashes = record.getJSONObject("vk").getJSONArray("hashes")
                if (hashes.length() > 0) {
                    val physical = vkTarget(hashes.getJSONObject(0), record.getString("newUri"), record.getString("newHash"))
                        .put("sha256", record.getString("newHash"))
                    val existing = vkRow(handle, "hashes", physical)
                    check(existing == null || sameJson(existing, physical)) { "Новый SHA-256 VK принадлежит другому файлу" }
                    if (existing == null) handle.insertOrThrow("hashes", null, ContentValues().apply {
                        put("sha256", physical.getString("sha256")); put("saved_uri", physical.getString("saved_uri"))
                        put("photo_key", physical.getString("photo_key"))
                    })
                }
                handle.setTransactionSuccessful()
            } finally { handle.endTransaction() }
        }
    }
    private fun vkTarget(before: JSONObject, uri: String, hash: String): JSONObject = JSONObject(before.toString()).apply {
        put("saved_uri", uri)
    }
    private fun vkRow(handle: SQLiteDatabase, table: String, row: JSONObject): JSONObject? {
        val result = if (table == "images") rows(handle, "SELECT * FROM images WHERE job_id=? AND photo_key=?",
            arrayOf(row.getString("job_id"), row.getString("photo_key")))
        else rows(handle, "SELECT * FROM hashes WHERE sha256=?", arrayOf(row.getString("sha256")))
        check(result.length() <= 1)
        return if (result.length() == 0) null else result.getJSONObject(0)
    }
    private fun readVk(block: (SQLiteDatabase) -> Unit) {
        if (!vkFile.exists()) return
        SQLiteDatabase.openDatabase(vkFile.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use(block)
    }
    private fun rows(handle: SQLiteDatabase, sql: String, args: Array<String>): JSONArray = handle.rawQuery(sql, args).use { cursor ->
        JSONArray().apply { while (cursor.moveToNext()) put(JSONObject().apply {
            cursor.columnNames.forEachIndexed { i, name -> put(name, when (cursor.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
                else -> error("Неизвестный формат индекса VK")
            }) }
        }) }
    }
    private fun write(key: String, value: JSONObject) {
        check(UUID.fromString(key).toString() == key)
        check(directory.isDirectory || directory.mkdirs())
        val file = AtomicFile(File(directory, "$key.json")); val stream = file.startWrite()
        try { stream.write(value.toString().toByteArray()); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
    }
    private fun read(key: String): JSONObject {
        check(UUID.fromString(key).toString() == key)
        return AtomicFile(File(directory, "$key.json")).openRead().bufferedReader().use { JSONObject(it.readText()) }
    }
    private fun strings(value: JSONArray): Set<String> = (0 until value.length()).map { value.getString(it) }.toSet()
    private fun sameJson(first: JSONObject, second: JSONObject): Boolean {
        fun canonical(value: Any?): String = when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}") { key ->
                JSONObject.quote(key) + ":" + canonical(value.get(key))
            }
            is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.get(it)) }
            is String -> JSONObject.quote(value)
            else -> value.toString()
        }
        return canonical(first) == canonical(second)
    }
    private fun nullable(value: JSONObject, key: String): String? = if (value.isNull(key)) null else value.getString(key)
}

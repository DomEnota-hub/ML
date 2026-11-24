package com.domenota.medialoader.data.archive

import android.content.Context
import android.util.AtomicFile
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Persisted membership for a SIMPLE ARCHIVE: never changes media files or MediaStore.
 * This is deliberately isolated from legacy encrypted Vault and from Private.
 * Any failure reading the index blocks mutations rather than silently resetting it.
 */
internal class SimpleArchiveStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir,
        "simple-media-archive-v1.json"))
    private val mutex = Mutex()
    private val current = MutableStateFlow<List<SimpleArchiveRecord>>(emptyList())
    val records = current.asStateFlow()

    suspend fun refresh(): List<SimpleArchiveRecord> = withContext(Dispatchers.IO) {
        mutex.withLock { read().also { current.value = it } }
    }

    suspend fun archive(rows: List<DownloadEntity>, source: ArchiveEntrySource,
        group: SimpleArchiveGroup? = null): List<SimpleArchiveRecord> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                require(rows.isNotEmpty()) { "Не выбраны файлы" }
                val now = System.currentTimeMillis()
                val selected = rows.map { row ->
                    check(!row.isPrivate && !row.hidden && row.state == DownloadState.COMPLETED) {
                        "Недоступный или приватный файл нельзя отправить в обычный Архив"
                    }
                    val uri = row.savedUri?.takeIf(String::isNotBlank)
                        ?: error("У файла нет подтверждённого адреса")
                    SimpleArchiveRecord(row.id, uri, row.mediaType,
                        row.originalName.ifBlank { "Файл" }, source, row.sizeBytes, now, group)
                }
                val next = SimpleArchivePolicy.archive(read(), selected)
                persist(next)
                current.value = next
                next
            }
        }

    suspend fun restore(ids: Set<String>): List<SimpleArchiveRecord> = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(ids.isNotEmpty()) { "Не выбраны файлы для возврата" }
            val existing = read()
            check(ids.all { id -> existing.any { it.catalogId == id } }) {
                "Архив изменился. Обнови список файлов"
            }
            val next = SimpleArchivePolicy.restore(existing, ids)
            persist(next)
            current.value = next
            next
        }
    }

    private fun read(): List<SimpleArchiveRecord> {
        val base = file.baseFile
        // AtomicFile.openRead recovers a legacy backup. A lone unfinished
        // write is not a proven empty index; preserve it and refuse mutation.
        if (!base.exists() && !File(base.path + ".bak").exists()) {
            if (File(base.path + ".new").exists()) {
                throw IOException("Незавершённая запись Архива. Данные не изменены")
            }
            return emptyList()
        }
        try {
            val data = JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            check(data.getInt("version") == 1) { "Неизвестная версия Архива" }
            val array = data.getJSONArray("files")
            val rows = (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                SimpleArchiveRecord(
                    catalogId = item.getString("catalogId"),
                    savedUri = item.getString("savedUri"),
                    mediaType = MediaType.valueOf(item.getString("mediaType")),
                    displayName = item.getString("displayName"),
                    source = ArchiveEntrySource.valueOf(item.getString("source")),
                    fileSizeBytes = if (item.isNull("fileSizeBytes")) null else item.getLong("fileSizeBytes"),
                    archivedAtMillis = item.getLong("archivedAtMillis"),
                    group = if (!item.has("group") || item.isNull("group")) null else
                        item.getJSONObject("group").let { group ->
                            SimpleArchiveGroup(SimpleArchiveGroupKind.valueOf(group.getString("kind")),
                                group.getString("key"), group.getString("name"))
                        },
                )
            }
            check(rows.map { it.catalogId }.distinct().size == rows.size) {
                "Повторяющиеся идентификаторы Архива"
            }
            return rows
        } catch (error: Exception) {
            throw IOException("Не удалось прочитать Архив. Данные не изменены", error)
        }
    }

    private fun persist(rows: List<SimpleArchiveRecord>) {
        val data = JSONObject().put("version", 1).put("files", JSONArray().apply {
            rows.forEach { item -> put(JSONObject()
                .put("catalogId", item.catalogId)
                .put("savedUri", item.savedUri)
                .put("mediaType", item.mediaType.name)
                .put("displayName", item.displayName)
                .put("source", item.source.name)
                .put("fileSizeBytes", item.fileSizeBytes ?: JSONObject.NULL)
                .put("archivedAtMillis", item.archivedAtMillis)
                .put("group", item.group?.let { group -> JSONObject()
                    .put("kind", group.kind.name).put("key", group.key).put("name", group.name)
                } ?: JSONObject.NULL)) }
        })
        val stream = file.startWrite()
        try {
            stream.write(data.toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw IOException("Не удалось сохранить Архив. Файлы не изменены", error)
        }
    }
}
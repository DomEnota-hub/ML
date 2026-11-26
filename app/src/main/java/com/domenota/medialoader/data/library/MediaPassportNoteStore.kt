package com.domenota.medialoader.data.library

import android.content.Context
import android.util.AtomicFile
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.storage.HistoricalRecoverySignals
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Only public catalog identities; titles and raw URI strings are never persisted. */
internal object MediaPassportNoteIdentity {
    const val MAX_LENGTH = 4000
    fun keys(ref: LibraryMediaRef): Set<String> {
        val raw = MediaOrganizationPolicy.keys(ref)
        if (raw.isEmpty()) return emptySet()
        val stable = if (ref.appDownloadId != null) raw.filter { it.startsWith("app:") }
            else raw.filter { it.startsWith("file-stat:") }.ifEmpty { raw.filter { it.startsWith("locator:") } }
        return stable.mapTo(linkedSetOf()) { key ->
            val bytes = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            buildString(64) {
                val hex = "0123456789abcdef"
                bytes.forEach { b -> val n = b.toInt() and 255; append(hex[n ushr 4]); append(hex[n and 15]) }
            }
        }
    }
}

/** Lazy per-admitted-generation index. A conflicting locator never chooses a winner. */
internal class MediaPassportNoteCatalog(refs: List<LibraryMediaRef>) {
    private val candidates = refs.distinct()
    private val owners by lazy {
        candidates.flatMap { ref -> MediaPassportNoteIdentity.keys(ref).map { it to ref } }
            .groupBy({ it.first }, { it.second })
    }
    fun resolve(keys: Set<String>): LibraryMediaRef? {
        if (keys.isEmpty() || keys.any { owners[it].orEmpty().size > 1 }) return null
        return keys.flatMap { owners[it].orEmpty() }.distinct().singleOrNull()
    }
    fun keys(ref: LibraryMediaRef): Set<String> =
        MediaPassportNoteIdentity.keys(ref).takeIf { resolve(it) == ref }.orEmpty()
}

/** App-private metadata only; an empty note deletes the record, never the media.
 * All operations share the existing public/private publication monitor. */
internal class MediaPassportNoteStore(context: Context, name: String = "media-passport-notes-v1.json") {
    private val context = context.applicationContext
    private val file = AtomicFile(File(this.context.noBackupFilesDir, name))
    private data class Record(val keys: Set<String>, val note: String)

    private fun readRecords(): List<Record> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val root = JSONObject(String(file.readFully(), Charsets.UTF_8))
        require(root.getInt("version") == 1)
        val rows = root.getJSONArray("notes")
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val identities = row.getJSONArray("keys")
            val keys = (0 until identities.length()).map { identities.getString(it) }.toSet()
            val note = row.getString("note")
            require(keys.isNotEmpty() && keys.all { it.matches(Regex("[0-9a-f]{64}")) })
            require(note.isNotBlank() && note.length <= MediaPassportNoteIdentity.MAX_LENGTH)
            Record(keys, note)
        }
    }

    private fun writeRecords(records: List<Record>) {
        val rows = JSONArray()
        records.forEach { row -> rows.put(JSONObject().put("keys", JSONArray(row.keys.sorted())).put("note", row.note)) }
        val stream = file.startWrite()
        try {
            stream.write(JSONObject().put("version", 1).put("notes", rows).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        }
        revision.value++
    }

    private fun current(ref: LibraryMediaRef, epoch: Long): Boolean =
        DuplicatePrivacyGuard.accepts(context, epoch, ref.appDownloadId ?: ref.key,
            ref.aliases + ref.key + ref.item.id)

    private fun requireAddress(catalog: MediaPassportNoteCatalog, ref: LibraryMediaRef, epoch: Long): Set<String> {
        HistoricalRecoverySignals.requireIdle()
        check(current(ref, epoch)) { "Файл недоступен" }
        return catalog.keys(ref).also { check(it.isNotEmpty()) { "Не подтверждён идентификатор файла" } }
    }

    private fun matching(records: List<Record>, catalog: MediaPassportNoteCatalog, ref: LibraryMediaRef,
                         keys: Set<String>): Record? {
        val hits = records.filter { it.keys.any(keys::contains) }
        check(hits.size <= 1 && hits.all { catalog.resolve(it.keys) == ref }) { "Неоднозначная заметка" }
        return hits.singleOrNull()
    }

    suspend fun read(catalog: MediaPassportNoteCatalog, ref: LibraryMediaRef, epoch: Long): String =
        withContext(Dispatchers.IO) {
            synchronized(DuplicatePrivacyGuard.lock) {
                val keys = requireAddress(catalog, ref, epoch)
                matching(readRecords(), catalog, ref, keys)?.note.orEmpty()
            }
        }

    suspend fun save(catalog: MediaPassportNoteCatalog, ref: LibraryMediaRef, epoch: Long,
                     expected: String, value: String): String = withContext(Dispatchers.IO) {
        require(value.length <= MediaPassportNoteIdentity.MAX_LENGTH)
        synchronized(DuplicatePrivacyGuard.lock) {
            val keys = requireAddress(catalog, ref, epoch)
            val records = readRecords()
            val old = matching(records, catalog, ref, keys)
            check(old?.note.orEmpty() == expected) { "Заметка изменилась; откройте заново" }
            val text = value.takeIf { it.isNotBlank() }.orEmpty()
            if (text != expected) {
                val next = records.filterNot { it === old } +
                    listOfNotNull(text.takeIf { it.isNotEmpty() }?.let { Record(keys + old?.keys.orEmpty(), it) })
                writeRecords(next)
            }
            text
        }
    }

    /** No text, metadata or counts for blocked/unresolved records leave this method. */
    suspend fun search(catalog: MediaPassportNoteCatalog, query: String, epoch: Long): List<LibraryMediaRef> =
        withContext(Dispatchers.IO) {
            val term = query.trim()
            if (term.isEmpty()) return@withContext emptyList()
            synchronized(DuplicatePrivacyGuard.lock) {
                HistoricalRecoverySignals.requireIdle()
                check(DuplicatePrivacyGuard.generation(context) == epoch) { "Доступ изменился" }
                val records = readRecords()
                val resolved = records.mapNotNull { record ->
                    catalog.resolve(record.keys)?.let { it to record }
                }.groupBy({ it.first }, { it.second })
                resolved.mapNotNull { (ref, matches) ->
                    val record = matches.singleOrNull() ?: return@mapNotNull null
                    if (ref.item.mediaType in setOf(MediaType.PHOTO, MediaType.VIDEO) && current(ref, epoch) &&
                        catalog.keys(ref).isNotEmpty() &&
                        record.note.contains(term, ignoreCase = true)) ref else null
                }
            }
        }

    companion object {
        private val revision = MutableStateFlow(0L)
        val changes = revision.asStateFlow()
    }
}

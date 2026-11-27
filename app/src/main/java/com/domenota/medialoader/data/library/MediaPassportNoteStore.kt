package com.domenota.medialoader.data.library

import android.content.Context
import android.util.AtomicFile
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.storage.HistoricalRecoverySignals
import java.io.File
import java.security.MessageDigest
import java.util.UUID
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

/** An independent note. Legacy single notes have no trustworthy creation timestamp. */
internal data class MediaPassportNote(val id: String, val text: String, val createdAt: Long?) {
    companion object {
        fun create(text: String) = MediaPassportNote(UUID.randomUUID().toString(), text, System.currentTimeMillis())
    }
}

/** App-private metadata only; deleting the last note removes the record, never the media.
 * All operations share the existing public/private publication monitor. */
internal class MediaPassportNoteStore(context: Context, name: String = "media-passport-notes-v1.json") {
    private val context = context.applicationContext
    private val file = AtomicFile(File(this.context.noBackupFilesDir, name))
    private data class Record(val keys: Set<String>, val notes: List<MediaPassportNote>)

    private fun validNotes(notes: List<MediaPassportNote>) {
        require(notes.map { it.id }.distinct().size == notes.size)
        require(notes.all { it.id.matches(Regex("(?:[0-9a-f-]{36}|legacy-[0-9a-f]{64})")) &&
            it.text.isNotBlank() && it.text.length <= MediaPassportNoteIdentity.MAX_LENGTH &&
            (it.createdAt == null || it.createdAt > 0) })
    }

    private fun readRecords(): List<Record> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val root = JSONObject(String(file.readFully(), Charsets.UTF_8))
        val version = root.getInt("version")
        require(version == 1 || version == 2)
        val rows = root.getJSONArray("notes")
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val identities = row.getJSONArray("keys")
            val keys = (0 until identities.length()).map { identities.getString(it) }.toSet()
            require(keys.isNotEmpty() && keys.all { it.matches(Regex("[0-9a-f]{64}")) })
            val notes = if (version == 1) {
                // Stable migration on the next atomic write. Never invent a legacy date.
                listOf(MediaPassportNote("legacy-${keys.minOrNull()!!}", row.getString("note"), null))
            } else {
                val entries = row.getJSONArray("entries")
                (0 until entries.length()).map { entry ->
                    val note = entries.getJSONObject(entry)
                    MediaPassportNote(note.getString("id"), note.getString("text"),
                        if (note.isNull("createdAt")) null else note.getLong("createdAt"))
                }
            }
            require(notes.isNotEmpty())
            validNotes(notes)
            Record(keys, notes)
        }
    }

    private fun writeRecords(records: List<Record>) {
        val rows = JSONArray()
        records.forEach { row ->
            val entries = JSONArray()
            row.notes.forEach { note -> entries.put(JSONObject().put("id", note.id).put("text", note.text)
                .put("createdAt", note.createdAt ?: JSONObject.NULL)) }
            rows.put(JSONObject().put("keys", JSONArray(row.keys.sorted())).put("entries", entries))
        }
        val stream = file.startWrite()
        try {
            stream.write(JSONObject().put("version", 2).put("notes", rows).toString().toByteArray(Charsets.UTF_8))
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

    suspend fun readNotes(catalog: MediaPassportNoteCatalog, ref: LibraryMediaRef, epoch: Long): List<MediaPassportNote> =
        withContext(Dispatchers.IO) {
            synchronized(DuplicatePrivacyGuard.lock) {
                val keys = requireAddress(catalog, ref, epoch)
                matching(readRecords(), catalog, ref, keys)?.notes.orEmpty()
            }
        }

    suspend fun saveNotes(catalog: MediaPassportNoteCatalog, ref: LibraryMediaRef, epoch: Long,
                          expected: List<MediaPassportNote>, value: List<MediaPassportNote>): List<MediaPassportNote> =
        withContext(Dispatchers.IO) {
            validNotes(value)
            synchronized(DuplicatePrivacyGuard.lock) {
                val keys = requireAddress(catalog, ref, epoch)
                val records = readRecords()
                val old = matching(records, catalog, ref, keys)
                check(old?.notes.orEmpty() == expected) { "Заметки изменились; откройте заново" }
                val previous = expected.associateBy { it.id }
                check(value.all { note -> previous[note.id]?.let { it.createdAt == note.createdAt }
                    ?: (note.createdAt != null && !note.id.startsWith("legacy-")) }) { "Недопустимое изменение даты" }
                if (value != expected) {
                    val next = records.filterNot { it === old } +
                        listOfNotNull(value.takeIf { it.isNotEmpty() }?.let { Record(keys + old?.keys.orEmpty(), it.toList()) })
                    writeRecords(next)
                }
                value.toList()
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
                        record.notes.any { it.text.contains(term, ignoreCase = true) }) ref else null
                }
            }
        }

    companion object {
        private val revision = MutableStateFlow(0L)
        val changes = revision.asStateFlow()
    }
}

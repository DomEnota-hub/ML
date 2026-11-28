package com.domenota.medialoader.data.library

import android.content.Context
import com.domenota.medialoader.data.playback.PublicMediaIdentity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Conservative proof for retaining public duplicate reports after moving an
 * unrelated item to Private. This is called under DuplicatePrivacyGuard.lock
 * on an IO worker, before mutating the privacy generation.
 *
 * Unknown, unreadable, oversized or ambiguous evidence means FULL invalidation.
 * Never infer unrelatedness from a display name, filename or a scan total.
 */
internal object DuplicatePrivacyRetention {
    private const val MAX_REPORT_BYTES = 48L * 1024 * 1024
    private const val MAX_SINGLE_FILE_BYTES = 12L * 1024 * 1024
    private const val MAX_REPORT_URIS = 256
    private const val MAX_REPORT_FILES = 200
    private const val MAX_VALUES = 200_000

    internal fun unrelatedMember(
        movedId: String, movedAliases: Set<String>,
        memberId: String, memberUri: String
    ): Boolean {
        if (movedId.isBlank() || movedAliases.isEmpty() ||
            memberId.isBlank() || memberUri.isBlank()) return false
        if (memberId == movedId || memberId in movedAliases ||
            memberUri == movedId || memberUri in movedAliases) return false
        val keys = runCatching { PublicMediaIdentity.uriKeys(memberUri) }.getOrNull()
            ?: return false
        return keys.none { it in movedAliases }
    }

    private class Inspection(val id: String, val aliases: Set<String>) {
        var fileCount = 0
        var bytes = 0L
        var values = 0
        var seenReport = false
        val uris = LinkedHashSet<String>()

        fun inspectValue(value: Any?) {
            if (++values > MAX_VALUES) error("privacy_report_value_limit")
            when (value) {
                is JSONObject -> {
                    val iterator = value.keys()
                    while (iterator.hasNext()) inspectValue(value.get(iterator.next()))
                }
                is JSONArray -> for (i in 0 until value.length()) inspectValue(value.get(i))
                is String -> {
                    if (value == id || value in aliases) error("privacy_reference_match")
                    if (value.startsWith("content://") || value.startsWith("file://")) {
                        if (!unrelatedMember(id, aliases, "external", value)) {
                            error("privacy_uri_match")
                        }
                        uris += value
                        if (uris.size > MAX_REPORT_URIS) error("privacy_uri_limit")
                    }
                }
            }
        }

        fun inspectFile(file: File) {
            if (++fileCount > MAX_REPORT_FILES) error("privacy_file_limit")
            val length = file.length()
            if (length <= 0 || length > MAX_SINGLE_FILE_BYTES) error("privacy_file_size")
            bytes += length
            if (bytes > MAX_REPORT_BYTES) error("privacy_total_size")
            val name = file.name
            if (!name.endsWith(".json") && !name.endsWith(".jsonl") &&
                !name.endsWith(".json.bak") && !name.endsWith(".json.new")) {
                error("privacy_unknown_report_format")
            }
            val raw = file.readText(Charsets.UTF_8)
            val records = if (name.endsWith(".jsonl")) raw.lineSequence().filter { it.isNotBlank() }
                else sequenceOf(raw)
            for (record in records) {
                val parsed = JSONTokener(record).nextValue()
                if (parsed !is JSONObject && parsed !is JSONArray) error("privacy_invalid_report")
                inspectValue(parsed)
            }
            seenReport = true
        }

        fun inspectPath(path: File) {
            if (!path.exists()) return
            if (path.isDirectory) {
                val children = path.listFiles() ?: error("privacy_unreadable_directory")
                for (child in children) inspectPath(child)
            } else if (path.isFile) inspectFile(path)
            else error("privacy_unknown_report_entry")
        }
    }

    /**
     * Any running/committing scan is conservatively invalidated. The persisted
     * report stores are examined at the exact privacy boundary while all their
     * writers are blocked by the shared lock. Raw JSONL shards and AtomicFile
     * companions are included, not just the currently displayed report.
     */
    fun provenUnrelated(context: Context, id: String, aliases: Set<String>): Boolean {
        if (id.isBlank() || aliases.isEmpty()) return false
        val music = DuplicateBackgroundScan.state.value
        val gallery = VisualDuplicateBackgroundScan.state.value
        if (music.running || music.committing || gallery.running || gallery.committing) return false
        return runCatching {
            val inspection = Inspection(id, aliases)
            val root = context.noBackupFilesDir
            listOf(
                "music-duplicate-results-v2",
                "duplicate-journal-music", "duplicate-journal-gallery"
            ).forEach { inspection.inspectPath(File(root, it)) }
            // AtomicFile may recover a .bak/.new companion during the next
            // read or process start. Inspect those bytes as well; a report is
            // not proven independent if its fallback copy contains the file.
            listOf(
                "music-duplicates-v1.json", "visual-duplicates-v1.json",
                "music-duplicate-trash-v1.json"
            ).forEach { name ->
                listOf("", ".bak", ".new").forEach { suffix ->
                    inspection.inspectPath(File(root, name + suffix))
                }
            }
            // Completed in-memory reports may not yet have been opened again
            // from disk. Include them and preliminary data in the proof.
            fun inspectMusic(groups: List<DuplicateGroup>) {
                for (group in groups) for (track in group.tracks) {
                    if (!unrelatedMember(id, aliases, track.id, track.uri)) error("privacy_live_audio")
                    inspection.uris += track.uri
                    if (inspection.uris.size > MAX_REPORT_URIS) error("privacy_uri_limit")
                }
            }
            fun inspectVisual(groups: List<VisualDuplicateGroup>) {
                for (group in groups) for (item in group.files) {
                    if (!unrelatedMember(id, aliases, item.id, item.uri)) error("privacy_live_visual")
                    inspection.uris += item.uri
                    if (inspection.uris.size > MAX_REPORT_URIS) error("privacy_uri_limit")
                }
            }
            music.session?.let { inspectMusic(it.result.groups) }
            music.partial?.let { inspectMusic(it.groups) }
            gallery.session?.let { inspectVisual(it.result.groups) }
            gallery.partial?.let { inspectVisual(it.groups) }
            if (!inspection.seenReport) return@runCatching false

            // Physical aliases matter: two distinct content URIs may resolve to
            // one inode. Only preserve a report when this can be disproven.
            if (aliases.any { it.startsWith("file-stat:") }) {
                runBlocking {
                    for (uri in inspection.uris) {
                        val observed = PublicMediaIdentity.capture(context, uri)
                        if (observed.none { it.startsWith("file-stat:") }) {
                            error("privacy_physical_identity_unavailable")
                        }
                        if (observed.any { it in aliases }) error("privacy_physical_match")
                    }
                }
            }
            true
        }.getOrDefault(false)
    }
}

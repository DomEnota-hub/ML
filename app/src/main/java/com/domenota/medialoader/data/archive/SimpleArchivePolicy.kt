package com.domenota.medialoader.data.archive

import com.domenota.medialoader.core.model.MediaType

/**
 * Ordinary, user-controlled library organization. Not a vault or a security boundary.
 * Files are NOT copied, encrypted, deleted or moved by this membership record.
 */
internal data class SimpleArchiveRecord(
    val catalogId: String,
    val savedUri: String,
    val mediaType: MediaType,
    val displayName: String,
    val source: ArchiveEntrySource,
    val fileSizeBytes: Long?,
    val archivedAtMillis: Long,
    val group: SimpleArchiveGroup? = null,
) {
    init {
        require(catalogId.isNotBlank() && savedUri.isNotBlank() && displayName.isNotBlank())
        require(fileSizeBytes == null || fileSizeBytes >= 0)
        require(archivedAtMillis >= 0)
    }
}

/** Presentation metadata only; never used as file identity or a physical path. */
internal enum class SimpleArchiveGroupKind { VIRTUAL_FOLDER, PUBLICATION }

internal data class SimpleArchiveGroup(
    val kind: SimpleArchiveGroupKind,
    val key: String,
    val name: String,
) {
    init { require(key.isNotBlank() && name.isNotBlank()) }
}

internal enum class ArchiveEntrySource { DOWNLOADER, GALLERY, MUSIC }

/** Source-level identity from the live ML catalog. Names alone are NEVER an identity. */
internal data class SimpleArchiveCandidate(
    val catalogId: String,
    val savedUri: String,
    val mediaType: MediaType,
    val fileSizeBytes: Long?,
)

internal object SimpleArchivePolicy {
    fun matches(record: SimpleArchiveRecord, candidate: SimpleArchiveCandidate): Boolean {
        if (record.mediaType != candidate.mediaType) return false
        if (record.fileSizeBytes != null && candidate.fileSizeBytes != null &&
            record.fileSizeBytes != candidate.fileSizeBytes) return false
        // A stable content URI lets the same file disappear across ML's app/phone aliases.
        if (record.savedUri == candidate.savedUri) return true
        // Do not hide a different file when Android reuses a MediaStore catalog ID.
        // If a URI changes following external reindexing, it needs explicit reconciliation.
        return false
    }

    fun isArchived(records: List<SimpleArchiveRecord>, candidate: SimpleArchiveCandidate): Boolean =
        records.any { matches(it, candidate) }

    /** Presentation only: hide a folder only when every known member has an exact archive match.
     * An empty folder, unknown member or changed identity is never proof of full archival. */
    fun fullyArchivedFolderIds(
        records: List<SimpleArchiveRecord>,
        members: Map<String, List<SimpleArchiveCandidate?>>,
    ): Set<String> {
        val byUri = records.groupBy { it.savedUri }
        return members.filterValues { rows ->
            rows.isNotEmpty() && rows.all { candidate ->
                candidate != null && isArchived(byUri[candidate.savedUri].orEmpty(), candidate)
            }
        }.keys
    }

    fun archive(
        existing: List<SimpleArchiveRecord>,
        selected: List<SimpleArchiveRecord>,
    ): List<SimpleArchiveRecord> {
        // Validate the whole transaction before deduplication: an unknown-size alias
        // must not mask two contradictory known sizes, or a reused catalog ID.
        val all = existing + selected
        check(all.groupBy { it.catalogId }.values.all { entries ->
            entries.map { it.savedUri }.distinct().size == 1
        }) { "Идентификатор файла изменился. Требуется обновить Архив" }
        check(all.groupBy { it.savedUri }.values.all { entries ->
            entries.map { it.mediaType }.distinct().size == 1 &&
                entries.mapNotNull { it.fileSizeBytes }.distinct().size <= 1
        }) { "Адрес файла изменился. Требуется обновить Архив" }
        val result = existing.toMutableList()
        for (item in selected) {
            check(result.none { it.catalogId == item.catalogId &&
                (it.savedUri != item.savedUri || it.mediaType != item.mediaType ||
                (it.fileSizeBytes != null && item.fileSizeBytes != null && it.fileSizeBytes != item.fileSizeBytes)) }) {
                "Идентификатор файла изменился. Требуется обновить Архив"
            }
            check(result.none { it.savedUri == item.savedUri && !matches(it, SimpleArchiveCandidate(
                item.catalogId, item.savedUri, item.mediaType, item.fileSizeBytes)) }) {
                "Адрес файла изменился. Требуется обновить Архив"
            }
            if (result.none { matches(it, SimpleArchiveCandidate(
                    item.catalogId, item.savedUri, item.mediaType, item.fileSizeBytes)) }) {
                result += item
            }
        }
        return result
    }

    /** IDs are those shown *inside* the Archive, not IDs from a mutable public list. */
    fun restore(existing: List<SimpleArchiveRecord>, selectedCatalogIds: Set<String>):
        List<SimpleArchiveRecord> = existing.filterNot { it.catalogId in selectedCatalogIds }
}
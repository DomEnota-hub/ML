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
) {
    init {
        require(catalogId.isNotBlank() && savedUri.isNotBlank() && displayName.isNotBlank())
        require(fileSizeBytes == null || fileSizeBytes >= 0)
        require(archivedAtMillis >= 0)
    }
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

    fun archive(
        existing: List<SimpleArchiveRecord>,
        selected: List<SimpleArchiveRecord>,
    ): List<SimpleArchiveRecord> {
        val result = existing.toMutableList()
        for (item in selected) {
            check(result.none { it.catalogId == item.catalogId &&
                (it.savedUri != item.savedUri || it.mediaType != item.mediaType ||
                (it.fileSizeBytes != null && item.fileSizeBytes != null && it.fileSizeBytes != item.fileSizeBytes)) }) {
                "Идентификатор файла изменился. Требуется обновить Архив"
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
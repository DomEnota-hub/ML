package com.domenota.medialoader.data.catalog.visibility

/**
 * A stored checkpoint is NOT permission to act on an obsolete virtual folder.
 * Validate the user's currently selected members and the exact original physical
 * signatures BEFORE accepting any replayed or previously completed journal.
 *
 * This is a pure, read-only guard; it cannot authorize a MediaStore mutation.
 */
internal fun selectiveArchivePlanMatches(
    checkpoint: SelectiveArchiveCheckpoint,
    folderId: String,
    assignedIds: List<String>,
    selected: List<NomediaMediaSignature>,
): Boolean {
    val originals=checkpoint.originals
    if(folderId.isBlank() || checkpoint.folderId!=folderId ||
        originals.isEmpty() || originals.size!=assignedIds.size ||
        originals.size!=selected.size || assignedIds.distinct().size!=assignedIds.size ||
        originals.map { it.catalogId }.distinct().size!=originals.size ||
        originals.map { it.catalogId }.toSet()!=assignedIds.toSet() ||
        originals.any { !it.wellFormed() }
    ) return false
    val signatures=originals.map { NomediaMediaSignature(it.displayName,it.sizeBytes) }
    return signatures.groupingBy { it }.eachCount()==
        selected.groupingBy { it }.eachCount()
}

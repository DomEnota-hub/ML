package com.domenota.medialoader.data.catalog.visibility

/**
 * Exact identity contract for a future selective archive operation.
 *
 * .nomedia on a shared parent, MediaStore trash and pending entries are NOT
 * selective archive implementations. The old v2 physical mover is retired.
 *
 * No file is moved here. This is a fail-closed evidence gate: even a verified
 * SHA-256 copy is insufficient to claim that the original Android MediaStore
 * identity and user collection/playlist links can be restored.
 */
internal data class SelectiveArchiveIdentity(
    val catalogId: String,
    val originalUri: String,
    val mediaStoreId: Long,
    val volume: String,
    val relativePath: String,
    val displayName: String,
    val sizeBytes: Long,
    val sha256: String,
    val modifiedAtMillis: Long,
    /** SHA-256 of the complete durable catalog, favorite, playlist and collection snapshot. */
    val referencesSha256: String,
) {
    fun wellFormed(): Boolean =
        catalogId.isNotBlank() && originalUri.startsWith("content://media/") &&
            mediaStoreId > 0 && originalUri.substringAfterLast("/").toLongOrNull() == mediaStoreId &&
            '?' !in originalUri && '#' !in originalUri && volume.isNotBlank() &&
            relativePath.isNotBlank() && !relativePath.startsWith("/") &&
            relativePath.endsWith("/") &&
            relativePath.split('/').dropLast(1).all {
                it.isNotBlank() && it != "." && it != ".." && '\\' !in it
            } &&
            displayName.isNotBlank() && displayName.none { it == '/' || it == '\\' || it.isISOControl() } &&
            sizeBytes >= 0 && modifiedAtMillis >= 0 &&
            sha256.isValidArchiveSha256() && referencesSha256.isValidArchiveSha256()
}

private fun String.isValidArchiveSha256() =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

internal enum class SelectiveArchiveBarrier {
    UNKNOWN_OR_CHANGED_SOURCE,
    IDENTITY_COLLISION,
    NOT_A_PARTIAL_PHYSICAL_FOLDER,
    UNVERIFIED_RESTORATION,
    SOURCE_NOT_PUBLIC,
    REFERENCES_CHANGED,
    SYSTEM_VISIBILITY_NOT_CONFIRMED,
}

/** Source preflight is read-only. A valid subset is NOT permission to mutate. */
internal fun verifySelectiveArchiveSources(
    selected: List<SelectiveArchiveIdentity>,
    selectedSignatures: List<NomediaMediaSignature>,
    physical: List<NomediaMediaSignature>,
): SelectiveArchiveBarrier? {
    if (selected.isEmpty() || selected.size != selectedSignatures.size ||
        selected.any { !it.wellFormed() } ||
        selected.map { it.catalogId }.distinct().size != selected.size ||
        selected.map { it.originalUri }.distinct().size != selected.size ||
        selected.map { it.volume to it.mediaStoreId }.distinct().size != selected.size
    ) return SelectiveArchiveBarrier.UNKNOWN_OR_CHANGED_SOURCE
    if (selected.map { NomediaMediaSignature(it.displayName,it.sizeBytes) }
            .groupingBy { it }.eachCount() !=
        selectedSignatures.groupingBy { it }.eachCount()
    ) return SelectiveArchiveBarrier.UNKNOWN_OR_CHANGED_SOURCE
    val verdict=archiveInventoryVerdict(selectedSignatures,physical)
    if (verdict !is ArchiveInventoryVerdict.RequiresSelectiveArchive)
        return SelectiveArchiveBarrier.NOT_A_PARTIAL_PHYSICAL_FOLDER
    return null
}

/**
 * The restore result must preserve the *same* URI/MediaStore ID, exact bytes,
 * physical location, timestamps and ALL durable links. A new MediaStore row
 * with matching name and bytes is NOT equivalent to the original.
 *
 * The platform scanner's actual visibility must be confirmed independently.
 * An unknown provider state never counts as success.
 */
internal fun verifySelectiveArchiveRestoration(
    before: SelectiveArchiveIdentity,
    after: SelectiveArchiveIdentity?,
    isPublicInAndroid: Boolean?,
    referencesRestored: Boolean?,
): SelectiveArchiveBarrier? {
    if (!before.wellFormed() || after == null || !after.wellFormed() ||
        before.catalogId != after.catalogId ||
        before.originalUri != after.originalUri ||
        before.volume != after.volume ||
        before.mediaStoreId != after.mediaStoreId ||
        before.relativePath != after.relativePath ||
        before.displayName != after.displayName ||
        before.sizeBytes != after.sizeBytes ||
        before.sha256 != after.sha256 ||
        before.modifiedAtMillis != after.modifiedAtMillis
    ) return SelectiveArchiveBarrier.UNVERIFIED_RESTORATION
    if (before.referencesSha256 != after.referencesSha256 || referencesRestored != true)
        return SelectiveArchiveBarrier.REFERENCES_CHANGED
    if (isPublicInAndroid != true) return SelectiveArchiveBarrier.SYSTEM_VISIBILITY_NOT_CONFIRMED
    return null
}

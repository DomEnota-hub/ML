package com.domenota.medialoader.data.catalog.visibility

import com.domenota.medialoader.core.database.CatalogAssignmentEntity
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.library.ExternalMediaEntry
import com.domenota.medialoader.data.library.LibraryMediaRef

/** A single projection shared by the full library, addressed routes and explicit folders.
 * Never recover a catalog member by a coincident numeric MediaStore ID. */
internal data class FolderVisibilityLibraryGuard(
    val policy: FolderVisibilityReadPolicy,
    val assignments: List<CatalogAssignmentEntity>,
    val external: List<ExternalMediaEntry>,
) {
    private val appFolders = assignments.associate { it.downloadId to it.folderId }
    private val externalByUri = external.groupBy { it.ref.item.savedUri }
    private val externalById = external.groupBy { it.ref.key }
    private val receipts = policy.snapshot.receipts.associateBy { it.downloadId }
    private val retired = policy.retiredPhoneLocators

    fun appExcluded(row: DownloadEntity): Boolean = policy.isExcluded(row.id, appFolders[row.id], row.savedUri)

    fun externalEntry(ref: LibraryMediaRef): ExternalMediaEntry? {
        if (ref.appDownloadId != null || ref.item.savedUri == null) return null
        return externalByUri[ref.item.savedUri].orEmpty().singleOrNull { entry ->
            entry.ref.key == ref.key || entry.ref.key in ref.aliases ||
                entry.ref.aliases.any { it.startsWith("file-stat:") && it in ref.aliases }
        }
    }

    fun excluded(ref: LibraryMediaRef): Boolean {
        val app = ref.appDownloadId
        if (app != null) return policy.isExcluded(app, appFolders[app], ref.item.savedUri) ||
            policy.isRetiredPhoneCollision(ref.key, ref.item.savedUri, ref.aliases,
                receipts[app]?.let { it.direction == VisibilityDirection.SHOW && it.destinationUri == ref.item.savedUri } == true)
        val entry = externalEntry(ref)
        // A restored external record has a stable logical key even when Android
        // assigns a new numeric ID. Old physical aliases never prove identity.
        val confirmed = entry?.let { item ->
            val receipt = receipts[item.ref.key]
            receipt?.direction == VisibilityDirection.SHOW && receipt.destinationUri == ref.item.savedUri &&
                item.ref.aliases.any { it.startsWith("file-stat:") && it in ref.aliases }
        } == true
        return policy.isExcluded(entry?.ref?.key ?: ref.key, entry?.folderId, ref.item.savedUri) ||
            policy.isRetiredPhoneCollision(ref.key, ref.item.savedUri, ref.aliases, confirmed)
    }

    fun explicitReceipt(ref: LibraryMediaRef): VisibilityMoveRecord? {
        val id = ref.appDownloadId ?: ref.key
        val folder = if (ref.appDownloadId != null) appFolders[id]
            else externalById[id].orEmpty().singleOrNull { it.ref.item.savedUri == ref.item.savedUri }?.folderId
        return folder?.let { policy.hiddenReceipt(id, it, ref.item.savedUri) }
    }

    fun explicitFolder(ref: LibraryMediaRef): String? = explicitReceipt(ref)?.folderId

    fun canOpen(row: DownloadEntity, explicitFolder: String?, hiddenRefs: List<LibraryMediaRef>): Boolean {
        if (policy.unknownPending) return false
        val hidden = hiddenRefs.singleOrNull { it.item.id == row.id && it.item.savedUri == row.savedUri }
        if (hidden != null) return explicitFolder != null && this.explicitFolder(hidden) == explicitFolder
        return !appExcluded(row) && !policy.isRetiredPhoneCollision(row.id, row.savedUri, emptySet())
    }

    /** Addressed collections and persisted queue aliases need the same guard as
     * visible lists. Include exact retired locators, not only current row IDs. */
    fun blockedKeys(): Set<String> = buildSet {
        addAll(retired)
        assignments.filter { it.folderId in policy.excludedFolders }.forEach { add(it.downloadId); add("app:${it.downloadId}") }
        external.filter { policy.isExcluded(it.ref.key,it.folderId,it.ref.item.savedUri) }.forEach { entry ->
            add(entry.ref.key); addAll(entry.ref.aliases); entry.ref.item.savedUri?.let { add(it); add("uri:$it") }
        }
        policy.snapshot.receipts.filter { it.direction == VisibilityDirection.HIDE }.forEach { receipt ->
            add(receipt.downloadId); add("app:${receipt.downloadId}")
            receipt.destinationUri?.let { add(it); add("uri:$it") }
        }
    }
}

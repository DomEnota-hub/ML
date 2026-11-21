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
    private fun locator(uri:String?):String? = uri?.replace("content://media/external_primary/","content://media/external/")
    private val externalByUri = external.groupBy { locator(it.ref.item.savedUri) }
    private val externalById = external.groupBy { it.ref.key }
    private val receipts = policy.snapshot.receipts.associateBy { it.downloadId }
    private val retired = policy.retiredPhoneLocators

    fun appExcluded(row: DownloadEntity): Boolean = policy.isExcluded(row.id, appFolders[row.id], row.savedUri)
    private fun physicalMatch(entry:ExternalMediaEntry,ref:LibraryMediaRef):Boolean =
        entry.ref.aliases.any { it.startsWith("file-stat:") && it in ref.aliases }
    private fun restored(entry:ExternalMediaEntry,ref:LibraryMediaRef):Boolean = receipts[entry.ref.key]?.let {
        it.direction==VisibilityDirection.SHOW && locator(it.destinationUri)==locator(ref.item.savedUri) && physicalMatch(entry,ref)
    }==true

    fun externalEntry(ref: LibraryMediaRef): ExternalMediaEntry? {
        if (ref.appDownloadId != null || ref.item.savedUri == null) return null
        return externalByUri[locator(ref.item.savedUri)].orEmpty().singleOrNull { entry ->
            if(entry.visibilityManaged) physicalMatch(entry,ref) else entry.ref.key == ref.key || entry.ref.key in ref.aliases || physicalMatch(entry,ref)
        }
    }

    fun excluded(ref: LibraryMediaRef): Boolean {
        // An old "restored" visibility receipt cannot exempt a selective
        // Archive identity, including aliases that survive media rescanning.
        if(ref.key in policy.archiveBlockedAliases ||
            ref.aliases.any(policy.archiveBlockedAliases::contains) ||
            ref.item.savedUri in policy.archiveBlockedUris ||
            ("uri:" + ref.item.savedUri) in policy.archiveBlockedAliases) return true
        val app = ref.appDownloadId
        if (app != null) return policy.isExcluded(app, appFolders[app], ref.item.savedUri) ||
            policy.isRetiredPhoneCollision(ref.key, ref.item.savedUri, ref.aliases,
                receipts[app]?.let { it.direction == VisibilityDirection.SHOW && locator(it.destinationUri) == locator(ref.item.savedUri) } == true)
        val candidates=externalByUri[locator(ref.item.savedUri)].orEmpty()
        val entry = externalEntry(ref)
        if(candidates.any { it.visibilityManaged } && entry==null) return true
        val confirmed = entry?.let { restored(it,ref) }==true
        return policy.isExcluded(entry?.ref?.key ?: ref.key, entry?.folderId, ref.item.savedUri) ||
            policy.isRetiredPhoneCollision(ref.key, ref.item.savedUri, ref.aliases, confirmed)
    }
    /** A new MediaStore address may be assigned on restore. The old logical
     * key is retained only after the actual file-stat agrees with the receipt. */
    fun normalizePublic(ref:LibraryMediaRef):LibraryMediaRef {
        val entry=externalEntry(ref)?.takeIf { restored(it,ref) } ?: return ref
        return ref.copy(key=entry.ref.key,aliases=(ref.aliases-retired)+entry.ref.key,
            item=ref.item.copy(id=entry.ref.key,savedUri=entry.ref.item.savedUri,
                createdAtEpochMillis=entry.ref.item.createdAtEpochMillis,originalName=entry.ref.item.originalName))
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
        val hidden = if(explicitFolder==null) null else hiddenRefs.singleOrNull { it.item.id == row.id && it.item.savedUri == row.savedUri }
        if (hidden != null) return explicitFolder != null && this.explicitFolder(hidden) == explicitFolder
        val receipt=receipts[row.id]
        val current=receipt?.direction==VisibilityDirection.SHOW && locator(receipt.destinationUri)==locator(row.savedUri)
        return !appExcluded(row) && !policy.isRetiredPhoneCollision(row.id, row.savedUri, emptySet(),current)
    }

    /** Addressed collections and persisted queue aliases need the same guard as
     * visible lists. Include exact retired locators, not only current row IDs. */
    fun blockedKeys(): Set<String> = buildSet {
        addAll(retired)
        policy.archiveBlockedIds.forEach { add(it); add("app:$it") }
        policy.archiveBlockedUris.forEach { add(it); add("uri:$it") }
        // Current addresses may be used again only after the addressed resolver
        // has checked the actual file-stat. Keep all other historical aliases retired.
        for(receipt in policy.snapshot.receipts.filter { it.direction==VisibilityDirection.SHOW }) {
            remove(receipt.downloadId)
            val uri=receipt.destinationUri ?: continue
            remove(uri);remove("uri:$uri")
            removeAll(com.domenota.medialoader.data.playback.PublicMediaIdentity.uriKeys(uri))
        }
        // Never let an unrelated legacy SHOW receipt un-block a current
        // selective Archive journal's exact original URI or catalogue ID.
        addAll(policy.archiveBlockedAliases)
        policy.archiveBlockedIds.forEach { add(it); add("app:$it") }
        policy.archiveBlockedUris.forEach { add(it); add("uri:$it") }
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

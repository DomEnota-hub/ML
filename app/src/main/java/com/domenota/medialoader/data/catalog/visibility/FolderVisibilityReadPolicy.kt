package com.domenota.medialoader.data.catalog.visibility

/** One database generation: mode and receipt rows must never be read as two
 * independent, potentially stale generations. */
internal data class FolderVisibilitySnapshot(
    val hiddenFolders: Set<String> = emptySet(),
    val receipts: List<VisibilityMoveRecord> = emptyList(),
    val retiredLocators:Set<String> = emptySet(),
) {
    init {
        require(receipts.map { it.downloadId }.toSet().size == receipts.size)
        require(receipts.all { it.phase == VisibilityMovePhase.CLEANED && it.destinationUri != null })
    }
}

/** Presentation is independent of vault/trash privacy. During a paused transfer
 * the affected folder remains excluded from public routes, even if some of its
 * members have already changed URI and the final folder mode has not committed. */
internal data class FolderVisibilityReadPolicy(
    val snapshot: FolderVisibilitySnapshot,
    val pendingFolder: String? = null,
    val unknownPending: Boolean = false,
    val pendingMembers: List<VisibilityMoveRecord> = emptyList(),
    val nomediaHiddenFolders: Set<String> = emptySet(),
) {
    val excludedFolders: Set<String> = snapshot.hiddenFolders + nomediaHiddenFolders + listOfNotNull(pendingFolder)
    private val receiptsById = snapshot.receipts.associateBy { it.downloadId }
    private val hiddenReceipts = snapshot.receipts.filter { it.direction == VisibilityDirection.HIDE }
    private val hiddenUris = snapshot.receipts.mapNotNullTo(mutableSetOf()) { if(it.direction==VisibilityDirection.HIDE) it.destinationUri else it.sourceUri }
    private val pendingIds = pendingMembers.mapTo(mutableSetOf()) { it.downloadId }
    private val pendingUris = pendingMembers.flatMapTo(mutableSetOf()) { listOfNotNull(it.sourceUri,it.logicalSourceUri,it.destinationUri) }

    fun isExcluded(id: String, folder: String?, uri: String?): Boolean =
        unknownPending || folder in excludedFolders || uri in hiddenUris || id in pendingIds || uri in pendingUris ||
            receiptsById[id]?.let { it.direction == VisibilityDirection.HIDE } == true

    /** An explicit folder selection is not an authorization to open arbitrary
     * media. Both membership and the completed receipt must agree exactly. */
    fun canOpenHidden(id: String, folder: String?, uri: String?, explicitFolder: String?): Boolean {
        if (unknownPending || folder == null || folder == pendingFolder || folder !in snapshot.hiddenFolders ||
            explicitFolder != folder || uri == null) return false
        val receipt = receiptsById[id] ?: return false
        return receipt.direction == VisibilityDirection.HIDE && receipt.folderId == folder && receipt.destinationUri == uri
    }

    fun hiddenReceipt(id: String, folder: String?, uri: String?): VisibilityMoveRecord? =
        receiptsById[id]?.takeIf { canOpenHidden(id, folder, uri, folder) }

    /** MediaStore row IDs can be recycled. Keep the original physical aliases
     * out of public resolution rather than attaching a different file to a
     * retained hidden identity. The current confirmed destination is exempt. */
    val retiredPhoneLocators: Set<String> = snapshot.retiredLocators +
        (snapshot.receipts + pendingMembers).flatMapTo(mutableSetOf()) { retiredLocatorsFor(it) }

    companion object {
        fun retiredLocatorsFor(record:VisibilityMoveRecord):Set<String> = buildSet {
            val origin=record.publicOrigin ?: return@buildSet
            if(record.direction==VisibilityDirection.SHOW && record.destinationUri==origin.uri) return@buildSet
            add(origin.uri); add("uri:${origin.uri}")
            val numeric=origin.uri.substringAfterLast('/').toLongOrNull() ?: return@buildSet
            val kind=when(origin.mime.substringBefore('/')) { "image"->"image"; "video"->"video"; "audio"->"audio"; else->return@buildSet }
            add("phone:$kind:$numeric")
            val volume=if(origin.volume=="external_primary") "external" else origin.volume
            add("media:$volume:$numeric")
            if(origin.volume=="external_primary") {
                val legacy=origin.uri.replace("/external_primary/","/external/")
                add(legacy); add("uri:$legacy")
            }
            if(record.direction==VisibilityDirection.HIDE) record.catalogSourceUri?.let { add(it); add("uri:$it") }
        }
    }

    fun isRetiredPhoneCollision(key: String, uri: String?, aliases: Set<String>, confirmedCurrent: Boolean = false): Boolean {
        if (confirmedCurrent) return false
        val retired = retiredPhoneLocators
        return key in retired || uri in retired || aliases.any(retired::contains)
    }
}

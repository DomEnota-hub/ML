package com.domenota.medialoader.data.catalog.visibility

/**
 * A batch may crash before creating every member's journal. A member with NO
 * journal has never been submitted to this archive mover, because each move
 * must first persist PREPARED and HIDE_INTENT. Still, independently verify
 * that such a member remains public and byte-for-byte unchanged.
 *
 * A missing/corrupt receipt is NEVER permission to invent a new MediaStore
 * row, guess a target path, or skip verification.
 */
internal class SelectiveArchiveUnjournaledProof(
    private val media:SelectiveArchiveMediaProbe,
    private val references:suspend (SelectiveArchiveIdentity)->String?,
) {
    suspend fun publicUnchanged(original:SelectiveArchiveIdentity):Boolean {
        if(!original.wellFormed())return false
        val first=media.inspect(original.originalUri) ?: return false
        if(!first.publicAndComplete() ||
            first.originalUri!=original.originalUri ||
            first.mediaStoreId!=original.mediaStoreId ||
            first.volume!=original.volume ||
            first.relativePath!=original.relativePath ||
            first.displayName!=original.displayName ||
            first.bytes!=original.sizeBytes ||
            first.modifiedAtMillis!=original.modifiedAtMillis
        )return false
        val digest=media.fingerprint(original.originalUri) ?: return false
        if(digest.bytes!=original.sizeBytes || digest.sha256!=original.sha256)return false
        val again=media.inspect(original.originalUri) ?: return false
        if(first!=again)return false
        return references(original)==original.referencesSha256
    }
}

/**
 * Pure preflight: no member may begin restore when any missing journal has
 * unknown identity. Only proven, untouched originals can be omitted from the
 * physical restoration loop.
 */
internal fun selectiveArchiveRecoveryCoverage(
    originals:List<SelectiveArchiveIdentity>,
    receipts:Map<String,SelectiveArchiveMoveReceipt>,
    verifiedPublicWithoutJournal:Set<String>,
):Boolean {
    if(originals.isEmpty() || originals.map { it.catalogId }.distinct().size!=originals.size ||
        receipts.keys.any { id->originals.none { it.catalogId==id } }
    )return false
    return originals.all { original ->
        val receipt=receipts[original.catalogId]
        if(receipt==null)original.catalogId in verifiedPublicWithoutJournal
        else receipt.catalogId==original.catalogId &&
            receipt.originalUri==original.originalUri &&
            receipt.originalSha256==original.sha256 &&
            receipt.phase!=SelectiveArchiveMovePhase.NEEDS_REVIEW
    }
}

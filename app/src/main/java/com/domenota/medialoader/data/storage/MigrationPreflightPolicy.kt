package com.domenota.medialoader.data.storage

/** Pure, deterministic decisions for the READ-ONLY migration inventory. */
internal object MigrationPreflightPolicy {
    const val CANDIDATE = "COPY_REQUIRED_REVIEW"
    const val RECENT = "ALREADY_RECENT_OR_UNCERTAIN"
    const val ONE_DAY_MS = 86_400_000L

    data class Input(
        val completed: Boolean,
        val hidden: Boolean,
        val privateItem: Boolean,
        val photoOrVideo: Boolean,
        val hasUri: Boolean,
        val mediaStoreUri: Boolean,
        val validHistoryDate: Boolean,
        val uniqueHistoryUri: Boolean,
        val ownerMatches: Boolean,
        val correctPath: Boolean,
        val nameMatches: Boolean,
        val supportedMime: Boolean,
        val positiveSize: Boolean,
        val sizeMatches: Boolean,
        val pendingOrTrashed: Boolean,
        val accessible: Boolean,
        val vkReferences: Int,
        val externalCatalogReferences: Int,
        val auxiliaryIndexesReadable: Boolean,
        val addedMillis: Long,
        val historyMillis: Long,
    )

    fun blockers(v: Input): List<String> = buildList {
        if (!v.completed) add("NOT_COMPLETED")
        if (v.hidden) add("HIDDEN_OR_TRASH")
        if (v.privateItem) add("PRIVATE_ITEM")
        if (!v.photoOrVideo) add("NOT_PHOTO_OR_VIDEO")
        if (!v.hasUri) add("NO_SAVED_URI")
        else if (!v.mediaStoreUri) add("NON_MEDIASTORE_URI")
        if (!v.validHistoryDate) add("INVALID_QUEUE_TIMESTAMP")
        if (!v.uniqueHistoryUri) add("SHARED_HISTORY_URI")
        if (!v.ownerMatches) add("FOREIGN_OR_UNKNOWN_OWNER")
        if (!v.correctPath) add("UNEXPECTED_RELATIVE_PATH")
        if (!v.nameMatches) add("FILE_NAME_MISMATCH")
        if (!v.supportedMime) add("UNSUPPORTED_FORMAT")
        if (!v.positiveSize) add("MISSING_MEDIASTORE_SIZE")
        if (!v.sizeMatches) add("HISTORY_SIZE_MISMATCH")
        if (v.pendingOrTrashed) add("PENDING_OR_TRASHED")
        if (!v.accessible) add("UNREADABLE_OR_MISSING_MEDIA")
        if (!v.auxiliaryIndexesReadable) add("AUXILIARY_INDEX_UNAVAILABLE")
        if (v.vkReferences > 0) add("VK_DATABASE_REFERENCES")
        if (v.externalCatalogReferences > 0) add("DEVICE_CATALOG_REFERENCES")
    }

    fun decision(input: Input): String {
        val errors = blockers(input)
        if (errors.isNotEmpty()) return "BLOCKED"
        // DATE_ADDED is a MediaStore insertion timestamp, not the true capture time.
        return if (input.addedMillis > input.historyMillis + ONE_DAY_MS)
            CANDIDATE else RECENT
    }
}

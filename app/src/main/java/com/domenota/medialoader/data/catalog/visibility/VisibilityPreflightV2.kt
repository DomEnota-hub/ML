package com.domenota.medialoader.data.catalog.visibility

/** A read-only, process-local HIDE/SHOW v2 snapshot. No journal, file writes
 * or private locators in presentation. A failed preview is not executable. */
internal enum class VisibilityPreflightIssue(val label: String) {
    CATALOG_SIZE("Размер в каталоге"),
    MEDIASTORE_SIZE("Размер в MediaStore"),
    STREAM_SIZE("Размер файла при чтении"),
    SIZE_UNCERTAIN("Неоднозначные размеры"),
    ACCESS("Доступ к файлу"),
    SOURCE("Исходный файл"),
    METADATA("Метаданные или состав"),
}

internal fun visibilityPreflightIssue(error: Exception): VisibilityPreflightIssue = when (error) {
    is VisibilityPlanSizeFailure -> when (error.kind) {
        VisibilityPlanSizeMismatch.CATALOG_ONLY -> VisibilityPreflightIssue.CATALOG_SIZE
        VisibilityPlanSizeMismatch.MEDIASTORE_ONLY -> VisibilityPreflightIssue.MEDIASTORE_SIZE
        VisibilityPlanSizeMismatch.STREAM_ONLY -> VisibilityPreflightIssue.STREAM_SIZE
        VisibilityPlanSizeMismatch.ALL_DIFFERENT -> VisibilityPreflightIssue.SIZE_UNCERTAIN
    }
    is SecurityException -> VisibilityPreflightIssue.ACCESS
    is java.io.IOException -> VisibilityPreflightIssue.SOURCE
    else -> VisibilityPreflightIssue.METADATA
}

/** Only counts and opaque confirmation token may reach Compose. */
internal data class VisibilityPreflightSummary(
    val token: String, val folderId: String, val hidden: Boolean,
    val ready: Int, val total: Int,
    val issueCounts: Map<VisibilityPreflightIssue, Int>,
) {
    init {
        require(token.isNotBlank() && folderId.isNotBlank())
        require(total in 0..VisibilityBatchRecord.MAX_MEMBERS && ready in 0..total)
        require(issueCounts.values.all { it > 0 } && issueCounts.values.sum() == total - ready)
    }
    val problems: Int get() = total - ready
    val canStart: Boolean get() = problems == 0
}

internal data class VisibilityPreflightSnapshot(
    val summary: VisibilityPreflightSummary,
    val verifiedBatch: VisibilityBatchRecord?,
) {
    init { require(summary.canStart == (verifiedBatch != null)) }
}

/** Compare immutable source evidence, not randomized operation IDs. Any member,
 * locator, fingerprint, origin or metadata change invalidates confirmation. */
internal fun visibilityPreflightMatches(
    frozen: VisibilityBatchRecord?, current: VisibilityBatchRecord,
): Boolean {
    if (frozen == null || frozen.folderId != current.folderId ||
        frozen.direction != current.direction || frozen.membership != current.membership ||
        frozen.members.size != current.members.size) return false
    return frozen.members.zip(current.members).all { (a, b) ->
        a.downloadId == b.downloadId && a.folderId == b.folderId &&
        a.direction == b.direction && a.sourceUri == b.sourceUri &&
        a.logicalSourceUri == b.logicalSourceUri && a.sourceName == b.sourceName &&
        a.sourceModifiedMillis == b.sourceModifiedMillis &&
        a.fingerprint == b.fingerprint && a.publicOrigin == b.publicOrigin &&
        a.catalogSourceUri == b.catalogSourceUri
    }
}

internal fun visibilityPreflightSummarize(
    token: String, folderId: String, hidden: Boolean, total: Int,
    ready: List<VisibilityMoveRecord>, failures: List<VisibilityPreflightIssue>,
): VisibilityPreflightSnapshot {
    require(ready.size + failures.size == total)
    val summary = VisibilityPreflightSummary(token,folderId,hidden,ready.size,total,
        failures.groupingBy { it }.eachCount())
    // Never start a partial folder by silently omitting failed files.
    val batch = if (summary.canStart) VisibilityBatchRecord(
        java.util.UUID.randomUUID().toString(),folderId,
        if (hidden) VisibilityDirection.HIDE else VisibilityDirection.SHOW,ready.toList(),
    ) else null
    return VisibilityPreflightSnapshot(summary,batch)
}

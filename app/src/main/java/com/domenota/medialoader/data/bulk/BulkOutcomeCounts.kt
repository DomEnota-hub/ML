package com.domenota.medialoader.data.bulk

/** A batch summary does not infer that an unaccounted file was lost. */
internal data class BulkOutcomeCounts(
    val selected: Int,
    val queued: Int,
    val completed: Int,
    val failed: Int,
    val cancelled: Int,
    val pendingOrUnknown: Int,
    val neverQueued: Int,
) {
    val terminal get() = completed + failed + cancelled

    companion object {
        fun from(
            selected: Int, queued: Int, completed: Int, failed: Int, cancelled: Int,
        ): BulkOutcomeCounts {
            require(listOf(selected, queued, completed, failed, cancelled).all { it >= 0 })
            val terminal = completed + failed + cancelled
            return BulkOutcomeCounts(
                selected = selected,
                queued = queued,
                completed = completed,
                failed = failed,
                cancelled = cancelled,
                pendingOrUnknown = (queued - terminal).coerceAtLeast(0),
                neverQueued = (selected - queued).coerceAtLeast(0),
            )
        }
    }
}

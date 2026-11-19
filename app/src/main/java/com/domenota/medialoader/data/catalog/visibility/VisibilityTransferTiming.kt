package com.domenota.medialoader.data.catalog.visibility

/** Clock-only transfer timing. Never log a URI, path, file name or proof bytes. */
internal fun visibilityDurationMs(startNs: Long, endNs: Long): Long =
    if (endNs < startNs) 0L else (endNs - startNs) / 1_000_000L


/** Redacted, invocation-local timings. Never infer earlier recovered files'
 * durations. No source URI, filename, fingerprint or private proof is logged. */
internal data class VisibilityBatchTimingSummary(
    val samples: Int, val p50Ms: Long, val p90Ms: Long, val p95Ms: Long,
)

internal fun visibilityBatchTimingSummary(values: List<Long>): VisibilityBatchTimingSummary {
    require(values.isNotEmpty() && values.size <= VisibilityBatchRecord.MAX_MEMBERS)
    require(values.all { it >= 0L })
    val sorted=values.sorted()
    fun percentile(percent:Int):Long {
        val rank=(sorted.size.toLong()*percent+99L)/100L
        return sorted[(rank.toInt()-1).coerceIn(0,sorted.lastIndex)]
    }
    return VisibilityBatchTimingSummary(sorted.size,percentile(50),percentile(90),percentile(95))
}

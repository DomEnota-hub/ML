package com.domenota.medialoader.data.editor

internal object VideoScrubPolicy {
    fun target(startMs: Long, horizontalPixels: Float, areaWidthPixels: Int,
        durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        val fraction = horizontalPixels.toDouble() / areaWidthPixels.coerceAtLeast(1)
        // Fine control near the start, progressively faster over a longer drag.
        val distance = kotlin.math.abs(fraction)
        val windowMs = minOf(durationMs, 600_000L)
        val delta = (kotlin.math.sign(fraction) * distance * distance * windowMs).toLong()
        return (startMs + delta).coerceIn(0L, durationMs)
    }
}

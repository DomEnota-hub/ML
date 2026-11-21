package com.domenota.medialoader.data.editor

internal object VideoScrubPolicy {
    fun target(startMs: Long, horizontalPixels: Float, areaWidthPixels: Int,
        durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        if (areaWidthPixels <= 0 || !horizontalPixels.isFinite()) return startMs.coerceIn(0L, durationMs)
        val fraction = horizontalPixels.toDouble() / areaWidthPixels
        // Linear signed distance; one viewport corresponds to at most 60 seconds.
        // Use equal gain in either direction, measured from the moving playback clock.
        val windowMs = minOf(durationMs, 60_000L)
        val delta = (fraction.coerceIn(-1.0, 1.0) * windowMs).toLong()
        return (startMs + delta).coerceIn(0L, durationMs)
    }
}

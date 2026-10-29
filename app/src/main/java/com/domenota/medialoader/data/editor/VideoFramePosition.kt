package com.domenota.medialoader.data.editor

/** MediaMetadataRetriever cannot return a frame at the exclusive end of a video or trim range. */
internal object VideoFramePosition {
    fun clamp(playheadMs: Long, trimStartMs: Long, trimEndMs: Long, durationMs: Long): Long {
        val lastVideoMs = (durationMs - 1L).coerceAtLeast(0L)
        val firstMs = trimStartMs.coerceIn(0L, lastVideoMs)
        val lastMs = (trimEndMs - 1L).coerceIn(firstMs, lastVideoMs)
        return playheadMs.coerceIn(firstMs, lastMs)
    }
}

package com.domenota.medialoader.data.editor

/** Times are relative to the trimmed clip, with an exclusive end. */
internal object TextTiming {
    fun startAt(layer: TextLayer, positionMs: Long, durationMs: Long): TextLayer {
        val end = (layer.endMs ?: durationMs).coerceAtMost(durationMs)
        return layer.copy(startMs = positionMs.coerceIn(0L, end - 1), endMs = end)
    }
    fun endAt(layer: TextLayer, positionMs: Long, durationMs: Long): TextLayer =
        layer.copy(endMs = positionMs.coerceIn(layer.startMs + 1, durationMs))
    fun seekPosition(positionMs: Long, durationMs: Long): Long =
        positionMs.coerceIn(0L, (durationMs - 1).coerceAtLeast(0L))
}

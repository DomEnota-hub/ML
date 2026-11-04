package com.domenota.medialoader.data.editor

import java.util.UUID

internal enum class VideoTextLaneHandle { MOVE, START, END }
internal object VideoTextLaneEdit {
    fun apply(layer: TextLayer, handle: VideoTextLaneHandle, targetMs: Long, durationMs: Long): TextLayer {
        require(durationMs > 0)
        val start = layer.startMs.coerceIn(0, durationMs - 1)
        val end = (layer.endMs ?: durationMs).coerceIn(start + 1, durationMs)
        val minimum = minOf(100L, end - start)
        return when (handle) {
            VideoTextLaneHandle.START -> layer.copy(startMs = targetMs.coerceIn(0, end - minimum), endMs = end)
            VideoTextLaneHandle.END -> layer.copy(startMs = start, endMs = targetMs.coerceIn(start + minimum, durationMs))
            VideoTextLaneHandle.MOVE -> {
                val from = targetMs.coerceIn(0, durationMs - (end - start))
                layer.copy(startMs = from, endMs = from + end - start)
            }
        }
    }
    fun duplicate(layer: TextLayer) = layer.copy(id = UUID.randomUUID().toString(),
        centerX = (layer.centerX + .035f).coerceAtMost(1f),
        centerY = (layer.centerY + .035f).coerceAtMost(1f))
}

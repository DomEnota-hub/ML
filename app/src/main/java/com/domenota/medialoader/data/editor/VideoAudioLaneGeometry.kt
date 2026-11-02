package com.domenota.medialoader.data.editor

import kotlin.math.roundToLong

/** Uses actual video item widths, including minimum widths of very short clips. */
internal class VideoAudioLaneScale(private val project: VideoProjectDraft, val widths: List<Float>) {
    init { require(widths.size == project.clips.size && widths.all { it > 0 && it.isFinite() }) }
    private val offsets = FloatArray(widths.size + 1).also { out ->
        for (i in widths.indices) out[i + 1] = out[i] + widths[i] + 3f
    }
    private val starts = LongArray(widths.size + 1).also { out ->
        for (i in widths.indices) out[i + 1] = out[i] + project.clips[i].durationMs
    }
    fun prefix(index: Int): Float = offsets[index.coerceIn(0, widths.size)]
    fun xAt(timeMs: Long): Float {
        val time = timeMs.coerceIn(0, project.durationMs)
        var index = 0
        while (index < widths.lastIndex && time >= starts[index + 1]) index++
        return offsets[index] + widths[index] * (time - starts[index]) / project.clips[index].durationMs
    }
    fun timeAt(x: Float): Long {
        var start = 0L
        for (i in widths.indices) {
            val local = x - prefix(i)
            if (local <= widths[i] || i == widths.lastIndex) return (start +
                (local.coerceIn(0f, widths[i]) / widths[i] * project.clips[i].durationMs).roundToLong())
                .coerceIn(0, project.durationMs)
            start += project.clips[i].durationMs
        }
        return project.durationMs
    }
}

internal enum class VideoAudioLaneHandle { MOVE, START, END }

internal object VideoAudioLaneEdit {
    fun apply(initial: VideoAudioTrackDraft, handle: VideoAudioLaneHandle,
        targetProjectMs: Long, projectDurationMs: Long): VideoAudioTrackDraft {
        val minimumSpan = minOf(100L, initial.outMs - initial.inMs)
        return when (handle) {
            VideoAudioLaneHandle.MOVE -> initial.copy(atMs = targetProjectMs.coerceIn(0,
                (projectDurationMs - minOf(100L, initial.durationMs)).coerceAtLeast(0)))
            VideoAudioLaneHandle.START -> {
                val source = (initial.inMs + (targetProjectMs - initial.atMs).toDouble() * initial.speed)
                    .roundToLong().coerceIn(0, initial.outMs - minimumSpan)
                val at = (initial.atMs + (source - initial.inMs).toDouble() / initial.speed).roundToLong().coerceAtLeast(0)
                initial.withRange(inMs = source).copy(atMs = at)
            }
            VideoAudioLaneHandle.END -> {
                val source = (initial.inMs + (targetProjectMs - initial.atMs).toDouble() * initial.speed)
                    .roundToLong().coerceIn(initial.inMs + minimumSpan, initial.sourceDurationMs)
                initial.withRange(outMs = source)
            }
        }
    }
}

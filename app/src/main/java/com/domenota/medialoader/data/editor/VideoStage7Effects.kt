package com.domenota.medialoader.data.editor

import kotlin.math.roundToLong

/**
 * No overlapping clips: these transitions consume only the tail and head
 * of adjacent clips, so project time, subtitles, audio and cuts stay stable.
 * A true cross-dissolve between two video streams is NOT simulated here.
 */
enum class VideoTransitionType(val label: String) {
    CUT("Без перехода"),
    DIP_BLACK("Затемнение"),
    DIP_WHITE("Через белый"),
    FLASH("Вспышка"),
    WIPE_BLACK("Шторка"),
}

data class VideoTransitionDraft(
    val type: VideoTransitionType = VideoTransitionType.CUT,
    /** Total transition length, half at each side of the cut. */
    val durationMs: Long = 600L,
) {
    init { require(durationMs in 100L..3000L) }
    val enabled: Boolean get() = type != VideoTransitionType.CUT
}

enum class VideoSpecialFxType(val label: String) {
    NONE("Без эффекта"),
    VIGNETTE("Виньетка"),
    SCANLINES("Сканирование"),
    CINEMA_BARS("Кинополосы"),
    PULSE("Мерцание"),
    FILM_GRAIN("Зернистость"),
    OLD_FILM("Старая плёнка"),
    VHS("VHS"),
    GLITCH("Глитч"),
    NEON_FRAME("Неоновая рамка"),
    SPOTLIGHT("Прожектор"),
}

data class VideoSpecialFxDraft(
    val type: VideoSpecialFxType = VideoSpecialFxType.NONE,
    val intensity: Float = .5f,
) {
    init { require(intensity.isFinite() && intensity in 0f..1f) }
    val enabled: Boolean get() = type != VideoSpecialFxType.NONE && intensity > 0f
}

/** Source-video timing, after per-clip speed and trim have been accounted for. */
data class VideoTransitionPhase(
    val type: VideoTransitionType,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val incoming: Boolean,
) {
    init {
        require(type != VideoTransitionType.CUT)
        require(sourceStartMs >= 0L && sourceEndMs > sourceStartMs)
    }

    fun progress(sourceLocalMs: Long): Float =
        ((sourceLocalMs - sourceStartMs).toFloat() /
            (sourceEndMs - sourceStartMs)).coerceIn(0f, 1f)

    fun coverage(sourceLocalMs: Long): Float {
        if (sourceLocalMs < sourceStartMs || sourceLocalMs > sourceEndMs) return 0f
        val p = progress(sourceLocalMs)
        return if (incoming) 1f - p else p
    }
}

internal object VideoTransitionTiming {
    /**
     * Return zero or two (incoming and outgoing) phases for the selected clip.
     * Half durations are bounded by both neighbor durations and the selected
     * clip's own duration; extreme playback speeds remain source-frame safe.
     */
    fun forClip(project: VideoProjectDraft, index: Int): List<VideoTransitionPhase> {
        require(index in project.clips.indices)
        val clip = project.clips[index]
        val phases = mutableListOf<VideoTransitionPhase>()
        if (index > 0) {
            val previous = project.clips[index - 1]
            val transition = previous.transitionOut
            if (transition.enabled) {
                val sideOutputMs = minOf(
                    transition.durationMs / 2L,
                    previous.durationMs / 2L,
                    clip.durationMs / 2L).coerceAtLeast(1L)
                val sourceSideMs = (sideOutputMs * clip.speed)
                    .roundToLong().coerceIn(1L, clip.sourceSpanMs)
                phases += VideoTransitionPhase(transition.type, 0L,
                    sourceSideMs, incoming = true)
            }
        }
        if (index < project.clips.lastIndex) {
            val transition = clip.transitionOut
            if (transition.enabled) {
                val next = project.clips[index + 1]
                val sideOutputMs = minOf(
                    transition.durationMs / 2L,
                    clip.durationMs / 2L,
                    next.durationMs / 2L).coerceAtLeast(1L)
                val sourceSideMs = (sideOutputMs * clip.speed)
                    .roundToLong().coerceIn(1L, clip.sourceSpanMs)
                phases += VideoTransitionPhase(transition.type,
                    clip.sourceSpanMs - sourceSideMs,
                    clip.sourceSpanMs, incoming = false)
            }
        }
        return phases
    }

    fun enabledCuts(project: VideoProjectDraft): Int =
        project.clips.dropLast(1).count { it.transitionOut.enabled }
}

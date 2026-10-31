package com.domenota.medialoader.data.editor

import java.util.UUID
import kotlin.math.roundToLong

enum class VideoGraphicKind(val label: String) {
    RECTANGLE("Прямоугольник"),
    CIRCLE("Круг"),
    STAR("Звезда"),
    ARROW("Стрелка"),
    EMOJI("Стикер"),
    IMAGE("Изображение"),
}

/** Positions and scale are fractions of the final video frame, not device pixels. */
data class VideoGraphicLayer(
    val id: String = UUID.randomUUID().toString(),
    val kind: VideoGraphicKind = VideoGraphicKind.STAR,
    val sourceUri: String? = null,
    val emoji: String = "⭐",
    val centerX: Float = .5f,
    val centerY: Float = .5f,
    val size: Float = .22f,
    val rotation: Float = 0f,
    val color: Int = 0xFFFFC629.toInt(),
    val opacity: Float = 1f,
    val startMs: Long = 0L,
    val endMs: Long? = null,
) {
    init {
        require(id.isNotBlank())
        require(kind != VideoGraphicKind.IMAGE || !sourceUri.isNullOrBlank())
        require(emoji.length <= 16)
        require(centerX.isFinite() && centerX in 0f..1f)
        require(centerY.isFinite() && centerY in 0f..1f)
        require(size.isFinite() && size in .03f.. .85f)
        require(rotation.isFinite() && rotation in -180f..180f)
        require(opacity.isFinite() && opacity in 0f..1f)
        require(startMs >= 0 && (endMs == null || endMs > startMs))
    }
    fun visibleAt(positionMs: Long): Boolean =
        positionMs >= startMs && (endMs == null || positionMs < endMs)

    fun within(durationMs: Long): VideoGraphicLayer? =
        if (startMs >= durationMs) null else copy(endMs = endMs?.coerceAtMost(durationMs))
}

internal data class VideoClipOverlaySegment(
    val texts: List<TextLayer>,
    val graphics: List<VideoGraphicLayer>,
    /** The effect receives source timestamps; subtract the source clip's in point. */
    val timeOffsetMs: Long,
)

/**
 * Time at the timeline is after speed conversion, but Media3's BitmapOverlay
 * sees source timestamps. Project times must be projected into the selected
 * source clip without slipping when a cut is sped up or slowed down.
 */
internal object VideoOverlayTimeline {
    fun forClip(
        project: VideoProjectDraft,
        clipIndex: Int,
        texts: List<TextLayer>,
        graphics: List<VideoGraphicLayer>,
    ): VideoClipOverlaySegment {
        val clip = project.clips[clipIndex]
        val clipStart = project.timelineStart(clipIndex)
        val clipEnd = clipStart + clip.durationMs
        fun bounds(startMs: Long, endMs: Long?): Pair<Long, Long>? {
            val start = maxOf(startMs, clipStart)
            val end = minOf(endMs ?: project.durationMs, clipEnd)
            if (start >= end) return null
            val relativeStart = ((start - clipStart) * clip.speed).roundToLong()
                .coerceIn(0L, clip.sourceSpanMs)
            val relativeEnd = ((end - clipStart) * clip.speed).roundToLong()
                .coerceIn(0L, clip.sourceSpanMs)
                .coerceAtLeast(relativeStart + 1L)
            return relativeStart to relativeEnd.coerceAtMost(clip.sourceSpanMs)
        }
        val localText = texts.mapNotNull { layer ->
            val times = bounds(layer.startMs, layer.endMs) ?: return@mapNotNull null
            layer.copy(startMs = times.first, endMs = times.second)
        }
        val localGraphics = graphics.mapNotNull { layer ->
            val times = bounds(layer.startMs, layer.endMs) ?: return@mapNotNull null
            layer.copy(startMs = times.first, endMs = times.second)
        }
        return VideoClipOverlaySegment(localText, localGraphics, clip.inMs)
    }

    fun validatedTexts(texts: List<TextLayer>, durationMs: Long): List<TextLayer> =
        texts.mapNotNull { it.within(durationMs) }

    fun validatedGraphics(graphics: List<VideoGraphicLayer>, durationMs: Long): List<VideoGraphicLayer> =
        graphics.mapNotNull { it.within(durationMs) }
}

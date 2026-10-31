package com.domenota.medialoader.data.editor

import kotlin.math.roundToLong

/**
 * Editor timeline model. Stage 1 loads one clip; subsequent stages may add
 * further clips and audio tracks without changing the workspace contract.
 *
 * No destructive mutations to downloaded media are performed here.
 */
data class VideoClipDraft(
    val id: String,
    val sourceUri: String,
    val sourceDurationMs: Long,
    val inMs: Long = 0L,
    val outMs: Long = sourceDurationMs,
    val speed: Float = 1f,
    val crop: CropFractions = CropFractions(),
    val cropPreset: CropAspectPreset = CropAspectPreset.FREE,
    val rotationDegrees: Int = 0,
    val mirrorHorizontal: Boolean = false,
    val mirrorVertical: Boolean = false,
    val color: VideoColorGrade = VideoColorGrade(),
    val transitionOut: VideoTransitionDraft = VideoTransitionDraft(),
    val specialFx: VideoSpecialFxDraft = VideoSpecialFxDraft(),
    val mediaKind: VideoClipMediaKind = VideoClipMediaKind.VIDEO,
) {
    init {
        require(id.isNotBlank())
        require(sourceUri.isNotBlank())
        require(sourceDurationMs > 0L)
        require(inMs >= 0L && inMs < outMs && outMs <= sourceDurationMs)
        require(speed.isFinite() && speed in MIN_SPEED..MAX_SPEED)
        if (mediaKind == VideoClipMediaKind.IMAGE) {
            require(sourceDurationMs in 500L..30_000L)
            require(inMs == 0L && outMs == sourceDurationMs && speed == 1f) {
                "Для фото длительность задаётся отдельно"
            }
        }
        require(rotationDegrees % 90 == 0)
        require(listOf(crop.left, crop.right, crop.top, crop.bottom).all { it.isFinite() && it in 0f..0.95f })
        require(crop.left + crop.right <= 0.95f && crop.top + crop.bottom <= 0.95f)
    }

    val sourceSpanMs: Long get() = outMs - inMs
    /** Project time is after speed conversion; source trim remains in original milliseconds. */
    val durationMs: Long get() = (sourceSpanMs / speed).roundToLong().coerceAtLeast(1L)

    fun sourceAt(localOutputMs: Long): Long =
        (inMs + (localOutputMs.coerceIn(0L, durationMs) * speed).roundToLong())
            .coerceIn(inMs, outMs)

    fun outputAt(sourceMs: Long): Long =
        ((sourceMs.coerceIn(inMs, outMs) - inMs) / speed).roundToLong()
            .coerceIn(0L, durationMs)

    companion object {
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f
    }
}

data class VideoAudioTrackDraft(
    val id: String,
    val sourceUri: String,
    /** Project-timeline position where this track starts. */
    val atMs: Long,
    val sourceDurationMs: Long,
    val inMs: Long = 0L,
    val outMs: Long = sourceDurationMs,
    val volume: Float = 1f,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
) {
    init {
        require(id.isNotBlank() && sourceUri.isNotBlank())
        require(atMs >= 0L)
        require(sourceDurationMs > 0L && inMs >= 0L &&
            outMs > inMs && outMs <= sourceDurationMs)
        require(volume.isFinite() && volume in 0f..1f)
        require(fadeInMs >= 0L && fadeOutMs >= 0L)
        require(fadeInMs + fadeOutMs <= durationMs) {
            "Сумма затуханий не должна превышать длину дорожки"
        }
    }

    val durationMs: Long get() = outMs - inMs
    val endAtMs: Long get() = atMs + durationMs
    fun gainAt(projectPositionMs: Long): Float {
        val local = projectPositionMs - atMs
        if (local < 0L || local >= durationMs) return 0f
        val startFactor = if (fadeInMs > 0L)
            (local.toFloat() / fadeInMs).coerceIn(0f, 1f) else 1f
        val endFactor = if (fadeOutMs > 0L)
            ((durationMs - local).toFloat() / fadeOutMs).coerceIn(0f, 1f) else 1f
        return volume * minOf(startFactor, endFactor)
    }
}

data class VideoTimelineLocation(
    val clipIndex: Int,
    val clipId: String,
    val positionWithinClipMs: Long,
    val absoluteSourceMs: Long,
)

data class VideoProjectDraft(
    val clips: List<VideoClipDraft>,
    val audioTracks: List<VideoAudioTrackDraft> = emptyList(),
) {
    init {
        require(clips.isNotEmpty() && clips.size <= MAX_CLIPS)
        require(audioTracks.size <= MAX_AUDIO_TRACKS)
        require(clips.map { it.id }.distinct().size == clips.size)
        require(audioTracks.map { it.id }.distinct().size == audioTracks.size)
        require(clips.sumOf { it.durationMs } > 0L)
    }

    val durationMs: Long get() = clips.sumOf { it.durationMs }

    /** Timeline-to-source map, with boundaries owned by the next clip. */
    fun locateAt(positionMs: Long): VideoTimelineLocation {
        val clamped = positionMs.coerceIn(0L, durationMs)
        var offset = 0L
        clips.forEachIndexed { index, clip ->
            val end = offset + clip.durationMs
            if (clamped < end || index == clips.lastIndex) {
                val within = (clamped - offset).coerceIn(0L, clip.durationMs)
                return VideoTimelineLocation(index, clip.id, within, clip.sourceAt(within))
            }
            offset = end
        }
        error("Unreachable: project has at least one clip")
    }

    fun trimClip(id: String, startMs: Long, endMs: Long): VideoProjectDraft =
        copy(clips = clips.map { clip ->
            if (clip.id == id) {
                require(clip.mediaKind == VideoClipMediaKind.VIDEO) {
                    "Для фотографии измени длительность, а не точки обрезки"
                }
                clip.copy(inMs = startMs, outMs = endMs)
            } else clip
        })

    fun setPhotoDuration(id: String, durationMs: Long): VideoProjectDraft {
        require(durationMs in 500L..30_000L)
        require(clips.any { it.id == id && it.mediaKind == VideoClipMediaKind.IMAGE })
        return copy(clips = clips.map { clip -> if (clip.id == id)
            clip.copy(sourceDurationMs = durationMs, inMs = 0L, outMs = durationMs)
            else clip })
    }

    fun setSpeed(id: String, speed: Float): VideoProjectDraft =
        copy(clips = clips.map { clip -> if (clip.id == id) {
            require(clip.mediaKind == VideoClipMediaKind.VIDEO) {
                "Для фотографии изменяй длительность"
            }
            clip.copy(speed = speed)
        } else clip })

    fun setGeometry(
        id: String,
        crop: CropFractions,
        preset: CropAspectPreset,
        rotationDegrees: Int,
        mirrorHorizontal: Boolean,
        mirrorVertical: Boolean,
    ): VideoProjectDraft = copy(clips = clips.map {
        if (it.id == id) it.copy(crop = crop, cropPreset = preset,
            rotationDegrees = rotationDegrees, mirrorHorizontal = mirrorHorizontal,
            mirrorVertical = mirrorVertical) else it
    })

    fun setColor(id: String, color: VideoColorGrade): VideoProjectDraft {
        require(indexOf(id) >= 0) { "Клип не найден" }
        return copy(clips = clips.map { if (it.id == id) it.copy(color = color) else it })
    }

    /** The outgoing cut is owned by the left clip; the last clip has no cut. */
    fun setTransitionAfter(clipId: String, transition: VideoTransitionDraft): VideoProjectDraft {
        val index = indexOf(clipId)
        require(index >= 0 && index < clips.lastIndex) { "Нет следующего клипа для перехода" }
        return copy(clips = clips.map { if (it.id == clipId)
            it.copy(transitionOut = transition) else it })
    }

    fun setSpecialFx(clipId: String, effect: VideoSpecialFxDraft): VideoProjectDraft {
        require(indexOf(clipId) >= 0) { "Клип не найден" }
        return copy(clips = clips.map { if (it.id == clipId)
            it.copy(specialFx = effect) else it })
    }

    fun addAudioTrack(track: VideoAudioTrackDraft): VideoProjectDraft =
        copy(audioTracks = audioTracks + track)

    fun removeAudioTrack(id: String): VideoProjectDraft {
        require(audioTracks.any { it.id == id })
        return copy(audioTracks = audioTracks.filterNot { it.id == id })
    }

    fun editAudioTrack(id: String, transform: (VideoAudioTrackDraft) -> VideoAudioTrackDraft): VideoProjectDraft {
        require(audioTracks.any { it.id == id })
        return copy(audioTracks = audioTracks.map { if (it.id == id) transform(it) else it })
    }

    /** Clip overlay to actual project bounds; a track past the project end is omitted. */
    fun visibleAudioTracks(): List<VideoAudioTrackDraft> = audioTracks.mapNotNull { track ->
        if (track.atMs >= durationMs) null
        else track.copy(outMs = minOf(track.outMs, track.inMs + durationMs - track.atMs),
            fadeInMs = minOf(track.fadeInMs, minOf(track.durationMs, durationMs - track.atMs)),
            fadeOutMs = minOf(track.fadeOutMs,
                (minOf(track.durationMs, durationMs - track.atMs) -
                    minOf(track.fadeInMs, minOf(track.durationMs, durationMs - track.atMs))).coerceAtLeast(0L)))
    }

    fun indexOf(id: String): Int = clips.indexOfFirst { it.id == id }

    fun timelineStart(index: Int): Long {
        require(index in clips.indices)
        return clips.take(index).sumOf { it.durationMs }
    }

    fun timelinePosition(index: Int, localMs: Long): Long {
        require(index in clips.indices)
        return timelineStart(index) + localMs.coerceIn(0L, clips[index].durationMs)
    }

    fun addAfter(id: String, newClips: List<VideoClipDraft>): VideoProjectDraft {
        require(newClips.isNotEmpty())
        val index = indexOf(id)
        require(index >= 0)
        // A transition belongs to the old cut. Move it to the inserted last
        // clip instead of multiplying it across every newly created cut.
        val oldCut = if (index < clips.lastIndex) clips[index].transitionOut
            else VideoTransitionDraft()
        val inserted = newClips.mapIndexed { offset, added ->
            if (offset == newClips.lastIndex) added.copy(transitionOut = oldCut)
            else added
        }
        return copy(clips = clips.take(index) +
            clips[index].copy(transitionOut = VideoTransitionDraft()) +
            inserted + clips.drop(index + 1))
    }

    fun splitAt(id: String, sourceMs: Long, rightId: String,
        minDurationMs: Long = 100L): VideoProjectDraft {
        val index = indexOf(id)
        require(index >= 0 && rightId.isNotBlank())
        val target = clips[index]
        require(target.mediaKind == VideoClipMediaKind.VIDEO) {
            "Фотографию нельзя разделять как видеозапись"
        }
        require(sourceMs - target.inMs >= minDurationMs &&
            target.outMs - sourceMs >= minDurationMs)
        return copy(clips = clips.take(index) +
            listOf(target.copy(outMs = sourceMs,
                transitionOut = VideoTransitionDraft()),
                target.copy(id = rightId, inMs = sourceMs)) +
            clips.drop(index + 1))
    }

    fun duplicate(id: String, newId: String): VideoProjectDraft {
        val index = indexOf(id)
        require(index >= 0 && newId.isNotBlank())
        return addAfter(id, listOf(clips[index].copy(
            id = newId, transitionOut = VideoTransitionDraft())))
    }

    fun move(id: String, toIndex: Int): VideoProjectDraft {
        val from = indexOf(id)
        require(from >= 0 && toIndex in clips.indices)
        if (from == toIndex) return this
        val mutable = clips.toMutableList()
        val moved = mutable.removeAt(from)
        mutable.add(toIndex, moved)
        return copy(clips = mutable)
    }

    fun replace(id: String, sourceUri: String, durationMs: Long,
        kind: VideoClipMediaKind = VideoClipMediaKind.VIDEO): VideoProjectDraft {
        val index = indexOf(id)
        require(index >= 0)
        return copy(clips = clips.map { clip ->
            if (clip.id == id) VideoClipDraft(id, sourceUri, durationMs,
                mediaKind = kind) else clip
        })
    }

    fun delete(id: String): VideoProjectDraft {
        require(clips.size > 1) { "Нельзя удалить последний клип" }
        val index = indexOf(id)
        require(index >= 0)
        val updated = clips.mapIndexed { at, clip ->
            if (index > 0 && at == index - 1)
                clip.copy(transitionOut = VideoTransitionDraft())
            else clip
        }
        return copy(clips = updated.filterNot { it.id == id })
    }

    companion object {
        const val MAX_CLIPS = 100
        const val MAX_AUDIO_TRACKS = 16
        fun single(id: String, sourceUri: String, durationMs: Long): VideoProjectDraft =
            VideoProjectDraft(listOf(VideoClipDraft(id, sourceUri, durationMs)))
    }
}

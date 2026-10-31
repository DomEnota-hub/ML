package com.domenota.medialoader.data.editor

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
) {
    init {
        require(id.isNotBlank())
        require(sourceUri.isNotBlank())
        require(sourceDurationMs > 0L)
        require(inMs >= 0L && inMs < outMs && outMs <= sourceDurationMs)
    }

    val durationMs: Long get() = outMs - inMs
}

data class VideoAudioTrackDraft(
    val id: String,
    val sourceUri: String,
    val atMs: Long,
    val durationMs: Long,
) {
    init {
        require(id.isNotBlank() && sourceUri.isNotBlank())
        require(atMs >= 0L && durationMs > 0L)
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
                return VideoTimelineLocation(index, clip.id, within, clip.inMs + within)
            }
            offset = end
        }
        error("Unreachable: project has at least one clip")
    }

    fun trimClip(id: String, startMs: Long, endMs: Long): VideoProjectDraft =
        copy(clips = clips.map { clip ->
            if (clip.id == id) clip.copy(inMs = startMs, outMs = endMs) else clip
        })

    companion object {
        const val MAX_CLIPS = 100
        const val MAX_AUDIO_TRACKS = 16
        fun single(id: String, sourceUri: String, durationMs: Long): VideoProjectDraft =
            VideoProjectDraft(listOf(VideoClipDraft(id, sourceUri, durationMs)))
    }
}

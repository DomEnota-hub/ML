package com.domenota.medialoader.data.editor

import kotlin.math.abs

/** Avoid repeated correction seeks starving a decoder which has only just caught up. */
internal object VideoAudioSyncDecision {
    /** Project placement and source trim have different origins; the end is exclusive. */
    fun sourcePositionMs(track: VideoAudioTrackDraft, projectPositionMs: Long): Long? {
        val localMs = projectPositionMs - track.atMs
        return if (localMs >= 0L && localMs < track.durationMs) track.sourceAt(localMs) else null
    }

    fun isJump(track: VideoAudioTrackDraft, projectPositionMs: Long,
        previousTrack: VideoAudioTrackDraft?, previousProjectMs: Long?, wasAudible: Boolean,
        playing: Boolean, wasPlaying: Boolean, elapsedMs: Long): Boolean {
        val naturalAdvance = if (playing && wasPlaying) elapsedMs else 0L
        return !wasAudible || previousProjectMs == null ||
            abs(projectPositionMs - previousProjectMs - naturalAdvance) > 250L ||
            previousTrack == null || previousTrack.inMs != track.inMs ||
            previousTrack.outMs != track.outMs || previousTrack.atMs != track.atMs ||
            previousTrack.speed != track.speed
    }

    fun shouldSeek(explicitJump: Boolean, ready: Boolean, playing: Boolean,
        driftMs: Long, sinceSeekMs: Long, continuousPlaybackMs: Long = Long.MAX_VALUE): Boolean =
        (explicitJump && driftMs != 0L) ||
            // Time spent buffering is not a correction cooldown: seeking as soon as
            // READY returns can repeatedly flush the decoder before it plays audio.
            (ready && abs(driftMs) > (if (playing) 180L else 20L) &&
                (!playing || (sinceSeekMs >= 750L && continuousPlaybackMs >= 750L)))
}

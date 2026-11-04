package com.domenota.medialoader.data.editor

import kotlin.math.abs

/** Follow the video clock without repeatedly flushing a running audio decoder. */
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
        driftMs: Long, sinceSeekMs: Long, continuousPlaybackMs: Long = Long.MAX_VALUE,
        masterBuffering: Boolean = false, trackSpeed: Float = 1f): Boolean =
        (explicitJump && driftMs != 0L) ||
            // Buffering is not a user pause. Hold without seeking backwards at each
            // video stall. Small running drift is recovered through tempo below.
            (!masterBuffering && ready && abs(driftMs / trackSpeed) > (if (playing) 1500f else 20f) &&
                (!playing || (sinceSeekMs >= 750L && continuousPlaybackMs >= 750L)))

    /** Media3 keeps pitch unchanged; correction is preview-only, never saved in the draft.
     * Use a steady correction, not a new tempo at every 75 ms sample (audio processors
     * must drain on tempo changes). Return to the draft speed inside the tolerance.
     */
    fun playbackSpeed(trackSpeed: Float, sourceDriftMs: Long, playing: Boolean,
        previousSpeed: Float = trackSpeed): Float {
        val outputDriftMs = sourceDriftMs / trackSpeed
        val correctingBehind = previousSpeed > trackSpeed + .001f
        val correctingAhead = previousSpeed < trackSpeed - .001f
        val correction = when {
            !playing -> 0f
            outputDriftMs < -80f || (correctingBehind && outputDriftMs < -20f) -> .05f
            outputDriftMs > 80f || (correctingAhead && outputDriftMs > 20f) -> -.05f
            else -> 0f
        }
        return trackSpeed * (1f + correction)
    }
}

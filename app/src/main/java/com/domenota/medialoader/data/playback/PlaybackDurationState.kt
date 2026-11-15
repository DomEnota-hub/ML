package com.domenota.medialoader.data.playback

/** Identity includes the actual session item and a revision issued on verified replacement. */
internal data class PlaybackDurationIdentity(val id: String, val uri: String?, val revision: String?)
internal data class PlaybackDurationState(val identity: PlaybackDurationIdentity? = null, val durationMs: Long = 0L) {
    fun update(current: PlaybackDurationIdentity?, reported: Long, verified: Long? = null): PlaybackDurationState {
        if (current == null) return PlaybackDurationState()
        val duration = reported.takeIf { it > 0L } ?: verified?.takeIf { it > 0L }
            ?: durationMs.takeIf { current == identity && it > 0L } ?: 0L
        return PlaybackDurationState(current,duration)
    }
}

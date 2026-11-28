package com.domenota.medialoader.data.playback

import androidx.media3.common.Player

/** Ephemeral, exact-source request. A normal activity launch cannot claim it. */
internal data class FloatingVideoHandoffRequest(val token: String, val itemId: String, val source: String) {
    fun matches(id: String, uri: String?): Boolean = itemId == id && source == uri
}

internal data class FloatingPlaybackState(
    val positionMs: Long, val play: Boolean, val repeatMode: Int, val speed: Float, val volume: Float,
) {
    companion object {
        fun capture(player: Player) = FloatingPlaybackState(
            player.currentPosition.coerceAtLeast(0L),
            player.playWhenReady && player.playbackState != Player.STATE_ENDED,
            player.repeatMode, player.playbackParameters.speed, player.volume,
        )
    }

    /** The destination remains silent until the source has stopped. */
    fun prepareDestination(player: Player) {
        player.pause()
        player.repeatMode = repeatMode
        player.setPlaybackSpeed(speed)
        player.volume = volume
        player.seekTo(positionMs)
    }
}

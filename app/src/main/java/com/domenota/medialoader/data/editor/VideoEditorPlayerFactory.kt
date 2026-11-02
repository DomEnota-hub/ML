package com.domenota.medialoader.data.editor

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/** Editor-specific public player operations, shared by the screen and actual codec tests. */
@OptIn(UnstableApi::class)
internal object VideoEditorPlayerFactory {
    fun builder(context: Context): ExoPlayer.Builder = ExoPlayer.Builder(context)

    /** A new effect list registers a NEW input stream and invalidates Media3's replay cache.
     * REDRAW alone cannot supply the first decoded frame to that new stream on pause.
     * Force a decoder seek then restore the exact logical position, without playing audio.
     */
    fun updateEffects(player: ExoPlayer, effects: List<androidx.media3.common.Effect>) {
        player.setVideoEffects(effects)
        if (!player.playWhenReady && player.mediaItemCount > 0) {
            val index = player.currentMediaItemIndex
            val position = player.currentPosition
            val nearby = if (position > 0L) position - 1L else 1L
            player.seekTo(index, nearby)
            player.seekTo(index, position)
        }
    }

}

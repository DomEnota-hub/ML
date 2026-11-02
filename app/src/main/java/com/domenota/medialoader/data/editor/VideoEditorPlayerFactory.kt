package com.domenota.medialoader.data.editor

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/** Editor-specific public player operations, shared by the screen and actual codec tests. */
@OptIn(UnstableApi::class)
internal object VideoEditorPlayerFactory {
    fun builder(context: Context): ExoPlayer.Builder = ExoPlayer.Builder(context)

    /** Changing effects registers a new GL stream. Paused frames need fresh decoder output.
     * Two back-to-back seeks can be coalesced by Media3 into no seek at all. Replacing
     * the same public MediaItem playlist creates fresh periods at the exact saved position;
     * decoding stays on Media3's worker threads. Playback uses its next incoming frame.
     */
    fun updateEffects(player: ExoPlayer, effects: List<androidx.media3.common.Effect>) {
        player.setVideoEffects(effects)
        if (!player.playWhenReady && player.mediaItemCount > 0) {
            val index = player.currentMediaItemIndex
            val position = player.currentPosition
            val items = List(player.mediaItemCount, player::getMediaItemAt)
            player.setMediaItems(items, index, position)
            player.prepare()
        }
    }
}

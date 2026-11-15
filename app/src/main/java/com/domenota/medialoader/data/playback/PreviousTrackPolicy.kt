package com.domenota.medialoader.data.playback

import androidx.media3.common.Player
import androidx.media3.common.ForwardingPlayer

/** Shared semantics for in-app and external MediaSession previous commands. */
internal object PreviousTrackPolicy{
    const val RESTART_THRESHOLD_MS=3000L
    fun restart(positionMs:Long,hasPrevious:Boolean):Boolean =
        !hasPrevious||positionMs>RESTART_THRESHOLD_MS
    fun previous(player:Player){
        if(player.mediaItemCount==0)return
        if(restart(player.currentPosition,player.hasPreviousMediaItem())){
            if(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))player.seekTo(0L)
        }else if(player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)){
            player.seekToPreviousMediaItem()
        }
    }
}

/** Apply the same boundary even when a platform controller sends the explicit
 * previous-media-item command instead of seekToPrevious(). No queue replacement. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MusicPreviousPlayer(private val delegate:Player):ForwardingPlayer(delegate){
    override fun seekToPrevious(){PreviousTrackPolicy.previous(delegate)}
    override fun seekToPreviousMediaItem(){PreviousTrackPolicy.previous(delegate)}
}

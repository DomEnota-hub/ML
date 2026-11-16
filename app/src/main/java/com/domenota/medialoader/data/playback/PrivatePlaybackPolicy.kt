package com.domenota.medialoader.data.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

internal object PrivatePlaybackPolicy {
    fun isPrivate(item: MediaItem?): Boolean = item?.mediaMetadata?.extras?.getBoolean("private_media") == true ||
        item?.localConfiguration?.uri?.path?.contains("/private-media/") == true

    fun blocked(item:MediaItem?,unlocked:Boolean,privateIds:Set<String>):Boolean {
        if(item==null)return false
        val private=isPrivate(item)
        return (!unlocked && private) || (!private && (item.mediaId in privateIds ||
            (privateIds.isNotEmpty() && run {
                val captured = item.mediaMetadata.extras?.getStringArrayList(PublicMediaIdentity.EXTRA_KEYS).orEmpty()
                val aliases = if (captured.isNotEmpty()) captured else
                    item.localConfiguration?.uri?.toString()?.let(PublicMediaIdentity::uriKeys).orEmpty()
                aliases.any { it in privateIds }
            })))
    }
    fun protect(player: Player, unlocked: Boolean, privateIds: Set<String>) {
        com.domenota.medialoader.core.logging.LibraryWorkCounters.increment(com.domenota.medialoader.core.logging.LibraryWork.PRIVATE_QUEUE_SCAN)
        val remove = (0 until player.mediaItemCount).filter { index -> blocked(player.getMediaItemAt(index),unlocked,privateIds) }
        if (player.currentMediaItemIndex in remove) player.stop()
        remove.asReversed().forEach(player::removeMediaItem)
    }
}

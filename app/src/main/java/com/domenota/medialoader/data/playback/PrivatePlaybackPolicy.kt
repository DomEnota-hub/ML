package com.domenota.medialoader.data.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

internal object PrivatePlaybackPolicy {
    fun isPrivate(item: MediaItem?): Boolean = item?.mediaMetadata?.extras?.getBoolean("private_media") == true ||
        item?.localConfiguration?.uri?.path?.contains("/private-media/") == true

    fun protect(player: Player, unlocked: Boolean, privateIds: Set<String>) {
        val remove = (0 until player.mediaItemCount).filter { index ->
            val item = player.getMediaItemAt(index)
            val private = isPrivate(item)
            (!unlocked && private) || (!private && (item.mediaId in privateIds ||
                (privateIds.isNotEmpty() && run {
                    val captured = item.mediaMetadata.extras?.getStringArrayList(PublicMediaIdentity.EXTRA_KEYS).orEmpty()
                    val aliases = if (captured.isNotEmpty()) captured else
                        item.localConfiguration?.uri?.toString()?.let(PublicMediaIdentity::uriKeys).orEmpty()
                    aliases.any { it in privateIds }
                })))
        }
        if (player.currentMediaItemIndex in remove) player.stop()
        remove.asReversed().forEach(player::removeMediaItem)
    }
}

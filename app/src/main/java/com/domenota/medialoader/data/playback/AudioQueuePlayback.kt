package com.domenota.medialoader.data.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import java.util.WeakHashMap

/** Preview is service-owned. Tapping an already playing track never replaces its queue. */
internal object AudioQueuePlayback {
    private data class NextBatch(val anchorId: String, val inserted: MutableList<String>)
    private val nextBatches = WeakHashMap<Player, NextBatch>()

    // "Play next" is FIFO: first requested track plays first; later selections go
    // immediately after the previously requested ones, never ahead of them.
    private fun nextTarget(player: Player): Int {
        val anchor = player.currentMediaItem?.mediaId.orEmpty()
        val current = player.currentMediaItemIndex.coerceAtLeast(0)
        val saved = nextBatches[player]
        if (saved == null || saved.anchorId != anchor) {
            nextBatches[player] = NextBatch(anchor, mutableListOf())
            return current + 1
        }
        val actual = (current + 1 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        val valid = saved.inserted.takeWhile { id ->
            actual.getOrNull(saved.inserted.indexOf(id)) == id
        }
        if (valid.size != saved.inserted.size) {
            saved.inserted.clear()
            saved.inserted.addAll(valid)
        }
        return (current + 1 + saved.inserted.size).coerceAtMost(player.mediaItemCount)
    }

    private fun isSameFile(selected: MediaItem, current: MediaItem?): Boolean {
        if (current == null || current.mediaMetadata.extras?.getBoolean("private_media") == true) return false
        val selectedKeys = selected.mediaMetadata.extras?.getStringArrayList(PublicMediaIdentity.EXTRA_KEYS).orEmpty().toSet()
        val currentKeys = current.mediaMetadata.extras?.getStringArrayList(PublicMediaIdentity.EXTRA_KEYS).orEmpty()
        return selected.mediaId == current.mediaId || current.mediaId in selectedKeys || currentKeys.any(selectedKeys::contains)
    }
    /** play() alone cannot start a stopped/failed ExoPlayer timeline in STATE_IDLE. */
    fun toggle(player: Player) {
        if (player.mediaItemCount == 0) return
        if (player.playbackState == Player.STATE_IDLE || player.playerError != null) {
            player.prepare()
            player.play()
        } else if (player.playWhenReady) player.pause() else player.play()
    }
    fun move(player: Player, from: Int, to: Int): Boolean {
        if (from !in 0 until player.mediaItemCount || to !in 0 until player.mediaItemCount) return false
        nextBatches.remove(player)
        player.moveMediaItem(from, to)
        return true
    }
    fun remove(player: Player, index: Int): Boolean {
        if (index !in 0 until player.mediaItemCount) return false
        nextBatches.remove(player)
        player.removeMediaItem(index)
        return true
    }
    fun enqueue(player: Player, item: MediaItem, next: Boolean): Boolean {
        if (item.mediaMetadata.extras?.getBoolean("private_media") == true ||
            player.currentMediaItem?.mediaMetadata?.extras?.getBoolean("private_media") == true) return false
        val count = player.mediaItemCount
        val existing = (0 until count).firstOrNull { isSameFile(item, player.getMediaItemAt(it)) }
        if (existing == player.currentMediaItemIndex) return true
        val target = if (next && count > 0) nextTarget(player).coerceIn(0, count) else count
        if (existing != null) {
            player.moveMediaItem(existing, (target - if (existing < target) 1 else 0).coerceIn(0, count - 1))
        } else player.addMediaItem(target, item)
        if (next && count > 0) {
            val batch = nextBatches[player]!!
            batch.inserted.remove(item.mediaId)
            batch.inserted.add(item.mediaId)
        }
        if (count == 0) player.prepare()
        return true
    }
    fun open(player: Player, items: List<MediaItem>, requestedId: String): Boolean {
        val selected = items.firstOrNull { it.mediaMetadata.extras?.getBoolean("private_media") != true &&
            (it.mediaId == requestedId || it.mediaMetadata.extras?.getStringArrayList(PublicMediaIdentity.EXTRA_KEYS)?.contains(requestedId) == true) }
            ?: return false
        return if (isSameFile(selected, player.currentMediaItem)) {
            if (player.playbackState == Player.STATE_IDLE || player.playerError != null) player.prepare()
            true
        } else preview(player, items, requestedId)
    }
    fun preview(player: Player, items: List<MediaItem>, requestedId: String): Boolean {
        val public = items.filter { it.mediaMetadata.extras?.getBoolean("private_media") != true }.distinctBy { it.mediaId }
        val index = public.indexOfFirst { it.mediaId == requestedId ||
            it.mediaMetadata.extras?.getStringArrayList(PublicMediaIdentity.EXTRA_KEYS)?.contains(requestedId) == true }
        if (index < 0) return false
        if (isSameFile(public[index], player.currentMediaItem)) {
            toggle(player)
        } else {
            nextBatches.remove(player)
            player.setMediaItems(public, index, 0L)
            player.prepare()
            player.play()
        }
        return true
    }
}

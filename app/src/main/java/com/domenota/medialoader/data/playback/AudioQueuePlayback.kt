package com.domenota.medialoader.data.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import java.util.WeakHashMap
import com.domenota.medialoader.core.logging.DetailedPerformance
import com.domenota.medialoader.core.logging.PerfWork

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
    /** Exact ordered content, including fields not covered by MediaMetadata.equals.
     * A changed physical URI, context, revision or identity must replace the queue. */
    private val contextKeys=listOf(AudioPlaybackMetadata.PLAYLIST_ID,AudioPlaybackMetadata.RESUME_CONTEXT,AudioPlaybackMetadata.CONTENT_REVISION)
    internal fun sameQueueItem(a:MediaItem,b:MediaItem):Boolean {
        if(a===b)return true
        val x=a.mediaMetadata.extras;val y=b.mediaMetadata.extras
        fun uri(item:MediaItem)=item.mediaMetadata.extras?.getString(AudioPlaybackMetadata.PUBLIC_URI)?:item.localConfiguration?.uri?.toString()
        if(a.mediaId!=b.mediaId || uri(a)!=uri(b) || a.mediaMetadata!=b.mediaMetadata ||
            (a.localConfiguration!=null && b.localConfiguration!=null && a.localConfiguration!=b.localConfiguration) ||
            a.clippingConfiguration!=b.clippingConfiguration || a.liveConfiguration!=b.liveConfiguration || a.requestMetadata!=b.requestMetadata)return false
        return contextKeys.all{x?.getString(it)==y?.getString(it)} &&
            x?.getBoolean("private_media",false)==y?.getBoolean("private_media",false) &&
            x?.getBoolean(AudioPlaybackMetadata.FILENAME_TITLE,false)==y?.getBoolean(AudioPlaybackMetadata.FILENAME_TITLE,false) &&
            x?.getLong(AudioPlaybackMetadata.VERIFIED_DURATION,0L)==y?.getLong(AudioPlaybackMetadata.VERIFIED_DURATION,0L) &&
            x?.getStringArrayList(PublicMediaIdentity.EXTRA_KEYS)==y?.getStringArrayList(PublicMediaIdentity.EXTRA_KEYS) &&
            x?.getStringArrayList(AudioPlaybackMetadata.LISTENING_KEYS)==y?.getStringArrayList(AudioPlaybackMetadata.LISTENING_KEYS)
    }
    internal fun sameOrderedQueue(player:Player,items:List<MediaItem>):Boolean =
        player.mediaItemCount==items.size && items.indices.all{sameQueueItem(player.getMediaItemAt(it),items[it])}
    private fun selectQueue(player:Player,items:List<MediaItem>,index:Int) {
        if(sameOrderedQueue(player,items)) {
            DetailedPerformance.measure(PerfWork.PLAYER_QUEUE_REUSE){player.seekTo(index,0L)}
            if(player.playbackState==Player.STATE_IDLE||player.playbackState==Player.STATE_ENDED||player.playerError!=null)player.prepare()
        } else {
            DetailedPerformance.measure(PerfWork.PLAYER_QUEUE_REPLACE){player.setMediaItems(items,index,0L);player.prepare()}
        }
    }
    /** play() alone cannot start a stopped/failed ExoPlayer timeline in STATE_IDLE. */
    fun toggle(player: Player, permit: AudioQueueReadPermit? = null) {
        if (permit == null) requireAudioQueueRead(emptyList(), null)
        else requireAudioQueueRead((0 until player.mediaItemCount).map(player::getMediaItemAt), permit)
        if (player.mediaItemCount == 0) return
        if (player.playbackState == Player.STATE_IDLE || player.playerError != null) {
            player.prepare()
            player.play()
        } else if (player.playWhenReady) player.pause() else player.play()
    }
    /** Capture actual playback traversal, including shuffle, ignoring repeat and position. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun playlistSnapshot(player: Player): List<MediaItem> {
        require(player.isCommandAvailable(Player.COMMAND_GET_TIMELINE))
        val timeline = player.currentTimeline
        val count = player.mediaItemCount
        val shuffle = player.shuffleModeEnabled
        require(count > 0 && timeline.windowCount == count)
        val seen = HashSet<Int>()
        val ordered = ArrayList<MediaItem>(count)
        var index = timeline.getFirstWindowIndex(shuffle)
        while (index in 0 until count && seen.add(index)) {
            val item = player.getMediaItemAt(index)
            require(!PrivatePlaybackPolicy.isPrivate(item) && item.mediaId.isNotBlank())
            ordered.add(item)
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
        }
        check(ordered.size == count && index == androidx.media3.common.C.INDEX_UNSET)
        return ordered
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
    fun enqueue(player: Player, item: MediaItem, next: Boolean, permit: AudioQueueReadPermit? = null): Boolean {
        if (permit == null) requireAudioQueueRead(emptyList(), null)
        else requireAudioQueueRead((0 until player.mediaItemCount).map(player::getMediaItemAt) + item, permit)
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
    /** An explicit Play/Shuffle action starts a frozen selection, even if its
     * first track is currently playing. Ordinary preview/toggle stays unchanged. */
    fun startSelection(player: Player, items: List<MediaItem>, shuffle: Boolean, requestedId: String? = null, permit: AudioQueueReadPermit? = null): Boolean {
        requireAudioQueueRead(items, permit)
        val public = items.filterNot { PrivatePlaybackPolicy.isPrivate(it) }.distinctBy { it.mediaId }
        if (public.isEmpty()) return false
        val start = if(requestedId!=null) public.indexOfFirst { it.mediaId==requestedId || it.mediaMetadata.extras?.getStringArrayList(PublicMediaIdentity.EXTRA_KEYS)?.contains(requestedId)==true } else if(shuffle) kotlin.random.Random.nextInt(public.size) else 0
        if(start<0)return false
        nextBatches.remove(player)
        player.shuffleModeEnabled = shuffle
        selectQueue(player,public,start)
        player.play()
        return true
    }
    /** A deliberate resume never toggles/replaces by a stale UI index. */
    fun resume(player:Player,items:List<MediaItem>,index:Int,session:ResumeQueueSession,position:Long,permit:AudioQueueReadPermit?=null):Boolean {
        requireAudioQueueRead(items, permit)
        if(index !in items.indices||items.any(PrivatePlaybackPolicy::isPrivate))return false
        val sameQueue=player.mediaItemCount==items.size&&player.currentMediaItemIndex==index&&
            (0 until player.mediaItemCount).all { i -> isSameFile(items[i],player.getMediaItemAt(i)) }&&
            player.currentMediaItem?.mediaMetadata?.extras?.getString(AudioPlaybackMetadata.RESUME_CONTEXT)==session.id
        if(sameQueue&&player.playbackState!=Player.STATE_ENDED){
            if(player.playbackState==Player.STATE_IDLE||player.playerError!=null)player.prepare()
            player.play();return true
        }
        nextBatches.remove(player)
        player.shuffleModeEnabled=session.shuffle
        player.repeatMode=session.repeat
        player.setPlaybackSpeed(session.speed)
        player.setMediaItems(items,index,position.coerceAtLeast(0L))
        player.prepare();player.play()
        return true
    }
    fun open(player: Player, items: List<MediaItem>, requestedId: String, permit: AudioQueueReadPermit? = null): Boolean {
        requireAudioQueueRead(items, permit)
        val selected = items.firstOrNull { it.mediaMetadata.extras?.getBoolean("private_media") != true &&
            (it.mediaId == requestedId || it.mediaMetadata.extras?.getStringArrayList(PublicMediaIdentity.EXTRA_KEYS)?.contains(requestedId) == true) }
            ?: return false
        return if (isSameFile(selected, player.currentMediaItem)) {
            if (player.playbackState == Player.STATE_IDLE || player.playerError != null) player.prepare()
            true
        } else preview(player, items, requestedId, permit)
    }
    fun preview(player: Player, items: List<MediaItem>, requestedId: String, permit: AudioQueueReadPermit? = null): Boolean {
        requireAudioQueueRead(items, permit)
        val public = items.filter { it.mediaMetadata.extras?.getBoolean("private_media") != true }.distinctBy { it.mediaId }
        val index = public.indexOfFirst { it.mediaId == requestedId ||
            it.mediaMetadata.extras?.getStringArrayList(PublicMediaIdentity.EXTRA_KEYS)?.contains(requestedId) == true }
        if (index < 0) return false
        if (isSameFile(public[index], player.currentMediaItem)) {
            toggle(player, permit)
        } else {
            nextBatches.remove(player)
            selectQueue(player,public,index)
            player.play()
        }
        return true
    }
}

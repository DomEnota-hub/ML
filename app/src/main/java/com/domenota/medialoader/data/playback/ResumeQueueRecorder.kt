package com.domenota.medialoader.data.playback

import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import kotlinx.coroutines.*

/** The service is the only owner of the real queue. UI never guesses positions.
 * Save on transitions/pause and every five seconds of actual playback. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class ResumeQueueRecorder(private val player:Player,private val scope:CoroutineScope,
    private val store:ResumeQueueStore,private val identity:PublicListeningIdentity):Player.Listener {
    private var current:ResumeQueueSession?=null
    private var ticker:Job?=null
    private var lastWritten:ResumeQueueSession?=null
    private var queueItems:List<MediaItem> = emptyList()
    private var queueKeys:List<Set<String>> = emptyList()
    private var contextId:String?=null
    private var playlistId:String?=null
    fun attach(){player.addListener(this);refresh()}
    private fun captureQueue(force:Boolean) {
        val count=player.mediaItemCount
        if(count !in 1..ResumeQueuePolicy.MAX_QUEUE) {queueItems=emptyList();queueKeys=emptyList();contextId=null;return}
        if(!force&&queueItems.size==count)return
        queueItems=(0 until count).map{player.getMediaItemAt(it)}
        queueKeys=queueItems.map(identity::keys)
        playlistId=queueItems.mapNotNull{it.mediaMetadata.extras?.getString(AudioPlaybackMetadata.PLAYLIST_ID)}.distinct().singleOrNull()
        val tags=queueItems.mapNotNull{it.mediaMetadata.extras?.getString(AudioPlaybackMetadata.RESUME_CONTEXT)}.distinct()
        contextId=if(queueKeys.any{it.isEmpty()})null else tags.singleOrNull()?:ResumeQueuePolicy.contextId(playlistId,queueKeys)
    }
    private fun persist(value:ResumeQueueSession?) {
        if(value==null)return
        // Avoid pointless writes while paused and never store an unopened queue.
        val prior=lastWritten
        if(prior!=null&&prior.copy(updatedAt=value.updatedAt)==value)return
        store.submit(value);lastWritten=value
    }
    fun refresh(forceQueue:Boolean=true) {
        captureQueue(forceQueue)
        val id=contextId
        val index=player.currentMediaItemIndex
        val valid=id!=null&&index in queueKeys.indices&&identity.keys(player.currentMediaItem).isNotEmpty()
        if(!valid) {persist(current);current=null;ticker?.cancel();ticker=null;return}
        if(current?.id!=id){persist(current);current=null}
        if(player.playbackState==Player.STATE_ENDED){
            store.finish(id!!);current=null;lastWritten=null;ticker?.cancel();ticker=null;return
        }
        if(player.isPlaying||current!=null) {
            val position=player.currentPosition.coerceAtLeast(0L)
            current=ResumeQueueSession(id!!,playlistId,queueKeys,index,position,player.duration,
                player.shuffleModeEnabled,player.repeatMode,player.playbackParameters.speed.coerceIn(.25f,4f),System.currentTimeMillis())
            persist(current)
        }
        if(player.isPlaying&&ticker?.isActive!=true) ticker=scope.launch{
            while(isActive&&player.isPlaying){delay(5000);refresh(false)}
        } else if(!player.isPlaying){ticker?.cancel();ticker=null}
    }
    override fun onEvents(player:Player,events:Player.Events) {
        val changed=events.contains(Player.EVENT_TIMELINE_CHANGED)||events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)
        // Metadata/timeline events can be emitted in quick succession. All writes
        // are coalesced by equality, but the latest logical queue stays exact.
        refresh(changed)
    }
    fun release(){player.removeListener(this);refresh();persist(current);ticker?.cancel();ticker=null}
}

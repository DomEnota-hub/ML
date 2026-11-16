package com.domenota.medialoader.data.playback

import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import kotlinx.coroutines.*

/** The service is the only owner of the real queue. UI never guesses positions.
 * Save on transitions/pause and every ten seconds of actual playback. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class ResumeQueueRecorder(private val player:Player,private val scope:CoroutineScope,
    private val store:ResumeQueueStore,private val identity:PublicListeningIdentity):Player.Listener {
    private var current:ResumeQueueSession?=null
    private var ticker:Job?=null
    private var lastWritten:ResumeQueueSession?=null
    private var queueItems:List<MediaItem> = emptyList()
    private var queueKeys:List<Set<String>> = emptyList()
    private var visibilityToken:Set<String>?=null
    private var contextLease=0L
    private var leasedContext:String?=null
    private var contextId:String?=null
    private var playlistId:String?=null
    private var occurrenceIds:List<String> = emptyList()
    private var finishedLease:Pair<String,Long>?=null
    fun attach(){player.addListener(this);refresh()}
    /** Full identity capture only for a changed timeline or explicit access refresh.
     * Media3 scalar events never enumerate/re-hash a 5000-track queue. */
    private fun captureQueue(force:Boolean):Boolean {
        val count=player.mediaItemCount
        if(count !in 1..ResumeQueuePolicy.MAX_QUEUE) {
            val changed=contextId!=null
            queueItems=emptyList();queueKeys=emptyList();occurrenceIds=emptyList();contextId=null;playlistId=null
            return changed
        }
        val visibility=identity.visibilityToken()
        if(!force&&queueItems.size==count&&visibility==visibilityToken)return false
        visibilityToken=visibility
        com.domenota.medialoader.core.logging.LibraryWorkCounters.increment(com.domenota.medialoader.core.logging.LibraryWork.RESUME_QUEUE_CAPTURE)
        val items=(0 until count).map{player.getMediaItemAt(it)}
        val keys=identity.keysForQueue(items)
        val playlist=items.mapNotNull{it.mediaMetadata.extras?.getString(AudioPlaybackMetadata.PLAYLIST_ID)}.distinct().singleOrNull()
        val tags=items.mapNotNull{it.mediaMetadata.extras?.getString(AudioPlaybackMetadata.RESUME_CONTEXT)}.distinct()
        val id=if(keys.any{it.isEmpty()})null else tags.singleOrNull()?:ResumeQueuePolicy.contextId(playlist,keys)
        queueItems=items
        if(id==contextId && playlist==playlistId && keys==queueKeys) return false
        queueKeys=keys;playlistId=playlist;contextId=id
        occurrenceIds=if(id==null)emptyList() else store.sessions.value.firstOrNull{it.id==id&&it.entries==keys}?.occurrenceIds
            ?: keys.indices.map { "$id:$it" }
        return true
    }
    private fun persist(value:ResumeQueueSession?) {
        if(value==null)return
        val prior=lastWritten
        // Do not copy/validate an entire session simply to compare its timestamp.
        if(prior!=null && sameResumeCheckpoint(prior,value))return
        store.submit(value,contextLease);lastWritten=value
    }
    fun refresh(forceQueue:Boolean=true) {
        val changed=captureQueue(forceQueue)
        val id=contextId
        val index=player.currentMediaItemIndex
        val valid=id!=null&&index in queueKeys.indices&&identity.keys(player.currentMediaItem).isNotEmpty()
        if(!valid) {persist(current);current=null;leasedContext=null;finishedLease=null;ticker?.cancel();ticker=null;return}
        if(current?.id!=id){persist(current);current=null}
        if(leasedContext!=id || changed) {contextLease=store.lease(id!!);leasedContext=id;finishedLease=null}
        if(player.playbackState==Player.STATE_ENDED){
            val lease=id!! to contextLease
            if(finishedLease!=lease) {store.finish(id,contextLease);finishedLease=lease}
            current=null;lastWritten=null;ticker?.cancel();ticker=null;return
        }
        finishedLease=null
        val position=player.currentPosition.coerceAtLeast(0L)
        if(current!=null||player.isPlaying&&(position>0L||index>0)) {
            val duration=player.duration
            val shuffle=player.shuffleModeEnabled
            val repeat=player.repeatMode
            val speed=player.playbackParameters.speed.coerceIn(.25f,4f)
            val previous=current
            if(previous==null || changed || previous.index!=index || previous.positionMs!=position ||
                previous.durationMs!=duration || previous.shuffle!=shuffle || previous.repeat!=repeat || previous.speed!=speed) {
                current=ResumeQueueSession(id!!,playlistId,queueKeys,index,position,duration,
                    shuffle,repeat,speed,System.currentTimeMillis(),occurrenceIds)
                persist(current)
            }
        }
        if(player.isPlaying&&ticker?.isActive!=true) ticker=scope.launch{
            while(isActive&&player.isPlaying){delay(10_000);refresh(false)}
        } else if(!player.isPlaying){ticker?.cancel();ticker=null}
    }
    override fun onEvents(player:Player,events:Player.Events) {
        val timeline=events.contains(Player.EVENT_TIMELINE_CHANGED)
        val metadata=events.contains(Player.EVENT_MEDIA_METADATA_CHANGED) &&
            queueItems.getOrNull(player.currentMediaItemIndex)!=player.currentMediaItem
        if(timeline||metadata||events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)||
            events.contains(Player.EVENT_POSITION_DISCONTINUITY)||events.contains(Player.EVENT_IS_PLAYING_CHANGED)||
            events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)||events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)||
            events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)||events.contains(Player.EVENT_REPEAT_MODE_CHANGED)||
            events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) refresh(timeline||metadata)
    }
    fun release(){player.removeListener(this);refresh();persist(current);ticker?.cancel();ticker=null}
}

/** Queue membership usually shares an immutable reference. Scalar equality needs
 * no reconstruction of occurrence IDs or validation of thousands of entries. */
internal fun sameResumeCheckpoint(a:ResumeQueueSession,b:ResumeQueueSession):Boolean =
    a.id==b.id && a.playlistId==b.playlistId && a.entries==b.entries && a.occurrenceIds==b.occurrenceIds &&
        a.index==b.index && a.positionMs==b.positionMs && a.durationMs==b.durationMs &&
        a.shuffle==b.shuffle && a.repeat==b.repeat && a.speed==b.speed

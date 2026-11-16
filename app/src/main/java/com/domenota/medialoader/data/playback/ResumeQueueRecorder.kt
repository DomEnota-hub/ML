package com.domenota.medialoader.data.playback

import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import kotlinx.coroutines.*

/** The service owns the queue. Only snapshot references and scalar player state
 * are read on its application looper; identity resolution/hashing runs off it. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class ResumeQueueRecorder(private val player:Player,private val scope:CoroutineScope,
    private val store:ResumeQueueStore,private val identity:PublicListeningIdentity,
    private val preparationDispatcher:CoroutineDispatcher=Dispatchers.Default):Player.Listener {
    private data class Membership(val id:String?, val playlist:String?, val keys:List<Set<String>>, val occurrences:List<String>)
    private data class Position(val index:Int,val position:Long,val duration:Long,val playing:Boolean,
        val status:Int,val shuffle:Boolean,val repeat:Int,val speed:Float,val at:Long)
    private data class Checkpoint(val membership:Membership,val state:Position) {
        val id get()=membership.id
        fun session()=ResumeQueueSession(requireNotNull(id),membership.playlist,membership.keys,state.index,state.position,state.duration,
            state.shuffle,state.repeat,state.speed,state.at,membership.occurrences)
        fun same(other:Checkpoint):Boolean = membership===other.membership && state.copy(at=0L,playing=false,status=0)==other.state.copy(at=0L,playing=false,status=0)
    }
    private var current:Checkpoint?=null
    private var ticker:Job?=null
    private var lastWritten:Checkpoint?=null
    private var queueItems:List<MediaItem> = emptyList()
    private var membership=Membership(null,null,emptyList(),emptyList())
    private var visibilityToken:PublicListeningIdentity.Token?=null
    private var contextLease=0L
    private var contextClearGeneration=0L
    private var leasedContext:String?=null
    private var finishedLease:Pair<String,Long>?=null
    private var captureGeneration=0L
    private var capturePending=false
    private var prepared=false
    private var pendingPlayback=false
    private var captureJob:Job?=null
    // A final in-flight checkpoint may finish after service release, using only
    // the frozen scalar position, never the already released player.
    private val captureScope=CoroutineScope(scope.coroutineContext.minusKey(Job)+SupervisorJob())
    private var finalPosition:Position?=null
    private var released=false
    private var recorderEpoch=0L
    fun attach(){recorderEpoch=store.beginRecorder();player.addListener(this);refresh()}
    private fun position()=Position(player.currentMediaItemIndex,player.currentPosition.coerceAtLeast(0L),player.duration,
        player.isPlaying,player.playbackState,player.shuffleModeEnabled,player.repeatMode,player.playbackParameters.speed.coerceIn(.25f,4f),System.currentTimeMillis())
    private suspend fun prepare(items:List<MediaItem>,gate:PublicListeningIdentity.Snapshot,previous:Membership,
        saved:List<ResumeQueueSession>):Membership {
        val keys=items.mapIndexed{index,item->if(index%64==0)currentCoroutineContext().ensureActive();gate.keys(item)}
        val playlist=items.mapNotNull{it.mediaMetadata.extras?.getString(AudioPlaybackMetadata.PLAYLIST_ID)}.distinct().singleOrNull()
        val tags=items.mapNotNull{it.mediaMetadata.extras?.getString(AudioPlaybackMetadata.RESUME_CONTEXT)}.distinct()
        val id=if(keys.any{it.isEmpty()})null else tags.singleOrNull()?:ResumeQueuePolicy.contextId(playlist,keys)
        if(id==previous.id&&playlist==previous.playlist&&keys==previous.keys)return previous
        val occurrences=if(id==null)emptyList() else saved.firstOrNull{it.id==id&&it.entries==keys}?.occurrenceIds
            ?:keys.indices.map{"$id:$it"}
        return Membership(id,playlist,keys,occurrences)
    }
    private fun captureQueue(force:Boolean):Boolean {
        val count=player.mediaItemCount
        val gate=identity.snapshot()
        if(!force && queueItems.size==count && gate.token==visibilityToken)return false
        val sameGate=gate.token==visibilityToken
        val items=if(count in 1..ResumeQueuePolicy.MAX_QUEUE)(0 until count).map{player.getMediaItemAt(it)} else emptyList()
        if(sameGate && queueItems.size==items.size && items.indices.all{queueItems[it]===items[it]} &&
            (capturePending || prepared || items.isEmpty())) return false
        val generation=++captureGeneration
        captureJob?.cancel();capturePending=false;prepared=false;pendingPlayback=false
        visibilityToken=gate.token
        if(count !in 1..ResumeQueuePolicy.MAX_QUEUE) {
            val changed=membership.id!=null
            queueItems=emptyList();membership=Membership(null,null,emptyList(),emptyList())
            return changed
        }
        queueItems=items
        val previous=membership
        val saved=store.sessions.value
        val clearGeneration=store.clearGeneration()
        val leases=store.leaseSnapshot()
        capturePending=true
        pendingPlayback=player.isPlaying && (player.currentPosition>0L || player.currentMediaItemIndex>0)
        com.domenota.medialoader.core.logging.LibraryWorkCounters.increment(com.domenota.medialoader.core.logging.LibraryWork.RESUME_QUEUE_CAPTURE)
        captureJob=captureScope.launch(start=CoroutineStart.UNDISPATCHED) {
            try {
                val result=withContext(preparationDispatcher){
                    com.domenota.medialoader.core.logging.DetailedPerformance.measure(com.domenota.medialoader.core.logging.PerfWork.RESUME_MEMBERSHIP){prepare(items,gate,previous,saved)}
                }
                if(generation!=captureGeneration||!store.isRecorderCurrent(recorderEpoch))return@launch
                capturePending=false
                // A preference or private-access change invalidates an in-flight result.
                if(identity.visibilityToken()!=gate.token){
                    if(!released)refresh(true)
                    return@launch
                }
                val changed=result!==membership
                membership=result;prepared=true
                val id=result.id
                if(current?.id!=id || changed) { persist(current); if(current?.id!=id)current=null }
                if(id!=null && (leasedContext!=id||changed)) {
                    contextLease=leases[id]?:0L;contextClearGeneration=clearGeneration;leasedContext=id;finishedLease=null
                }
                val state=if(released)finalPosition else position()
                if(state!=null) checkpoint(state,changed,pendingPlayback)
                pendingPlayback=false
                if(!released)updateTicker()
            } catch(e:CancellationException){throw e}
            catch(_:Exception){
                if(generation==captureGeneration){capturePending=false;membership=Membership(null,null,emptyList(),emptyList());
                    com.domenota.medialoader.core.logging.AppLog.w("Audio","Resume membership preparation failed")}
            } finally {if(released && generation==captureGeneration)captureScope.cancel()}
        }
        return false
    }
    private fun persist(value:Checkpoint?) {
        if(value==null)return
        val prior=lastWritten
        if(prior!=null && prior.same(value))return
        // Session validation (including all occurrence IDs) and JSON are worker work.
        store.submitPrepared(contextLease,contextClearGeneration,value::session);lastWritten=value
    }
    private fun checkpoint(state:Position,changed:Boolean,wasPlayingWhilePreparing:Boolean=false) {
        val id=membership.id
        val index=state.index
        val valid=id!=null&&index in membership.keys.indices&&membership.keys[index].isNotEmpty()&&
            identity.keys(queueItems.getOrNull(index)).isNotEmpty()
        if(!valid){persist(current);current=null;leasedContext=null;finishedLease=null;return}
        if(current?.id!=id){persist(current);current=null}
        if(leasedContext!=id){contextLease=store.lease(id!!);contextClearGeneration=store.clearGeneration();leasedContext=id;finishedLease=null}
        if(state.status==Player.STATE_ENDED){
            val lease=id!! to contextLease
            if(finishedLease!=lease){store.finish(id,contextLease);finishedLease=lease}
            current=null;lastWritten=null;return
        }
        finishedLease=null
        if(current!=null||wasPlayingWhilePreparing||state.playing&&(state.position>0L||index>0)) {
            val previous=current
            val next=Checkpoint(membership,state)
            if(previous==null || changed || !previous.same(next)) {
                current=next
                persist(current)
            }
        }
    }
    private fun updateTicker(){
        if(player.isPlaying&&ticker?.isActive!=true) ticker=scope.launch{
            while(isActive&&player.isPlaying){delay(10_000);refresh(false)}
        } else if(!player.isPlaying){ticker?.cancel();ticker=null}
    }
    fun refresh(forceQueue:Boolean=true) {
        if(released)return
        val oldLease=contextLease
        val old=current
        val changed=captureQueue(forceQueue)
        if(capturePending){ // Never write an old membership with a new queue's index.
            if(player.isPlaying && (player.currentPosition>0L||player.currentMediaItemIndex>0))pendingPlayback=true
            if(old!=null && oldLease==contextLease)persist(old)
        } else checkpoint(position(),changed)
        updateTicker()
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
    fun release(){
        if(released)return
        player.removeListener(this)
        refresh(true);finalPosition=position();released=true
        persist(current);ticker?.cancel();ticker=null
        if(!capturePending)captureScope.cancel()
    }
}

/** Queue membership usually shares an immutable reference. Scalar equality needs
 * no reconstruction of occurrence IDs or validation of thousands of entries. */
internal fun sameResumeCheckpoint(a:ResumeQueueSession,b:ResumeQueueSession):Boolean =
    a.id==b.id && a.playlistId==b.playlistId && (a.entries===b.entries||a.entries==b.entries) &&
        (a.occurrenceIds===b.occurrenceIds||a.occurrenceIds==b.occurrenceIds) &&
        a.index==b.index && a.positionMs==b.positionMs && a.durationMs==b.durationMs &&
        a.shuffle==b.shuffle && a.repeat==b.repeat && a.speed==b.speed

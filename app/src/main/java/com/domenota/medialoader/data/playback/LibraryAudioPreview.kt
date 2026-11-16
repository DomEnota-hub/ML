package com.domenota.medialoader.data.playback

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import com.domenota.medialoader.data.library.LibraryMediaRef
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class AudioPreviewState(val currentId: String? = null, val playing: Boolean = false, val identities: Set<String> = emptySet(),
    val queue: List<androidx.media3.common.MediaItem> = emptyList(),val connected:Boolean=false,val currentItem:MediaItem?=null,val queueGeneration:Long=0L)

/** One controller owned by the library ViewModel, not by the add-tracks route. */
internal class LibraryAudioPreview(context: Context) {
    private val context = context.applicationContext
    private var future: ListenableFuture<MediaController>? = null
    private var connected: MediaController? = null
    private val mutableState = MutableStateFlow(AudioPreviewState())
    val state = mutableState.asStateFlow()
    private var queueOwner:Player?=null
    private var queueTimeline:androidx.media3.common.Timeline?=null
    private var queueSnapshot:List<MediaItem> = emptyList()
    private var queueGeneration=0L
    private var identityItem:MediaItem?=null
    private var identityKeys:Set<String> = emptySet()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { sync(player,events.contains(Player.EVENT_TIMELINE_CHANGED)) }
    }
    private fun sync(player: Player, forceQueue:Boolean=false) {
        val current=player.currentMediaItem
        if(queueOwner!==player || queueTimeline!==player.currentTimeline || forceQueue || queueSnapshot.size!=player.mediaItemCount ||
            queueSnapshot.getOrNull(player.currentMediaItemIndex)!=current) {
            queueOwner=player;queueTimeline=player.currentTimeline
            val candidate=com.domenota.medialoader.core.logging.DetailedPerformance.measure(com.domenota.medialoader.core.logging.PerfWork.PLAYER_QUEUE_CAPTURE){
                (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
            }
            if(candidate.size!=queueSnapshot.size || candidate.indices.any{!AudioQueuePlayback.sameQueueItem(candidate[it],queueSnapshot[it])}){
                queueSnapshot=candidate;queueGeneration++
                com.domenota.medialoader.core.logging.LibraryWorkCounters.increment(com.domenota.medialoader.core.logging.LibraryWork.AUDIO_QUEUE_CAPTURE)
            }
        }
        if(identityItem!==current) {
            identityItem=current
            identityKeys=current?.mediaMetadata?.extras?.getStringArrayList(PublicMediaIdentity.EXTRA_KEYS).orEmpty().toSet()
        }
        mutableState.value = if (current?.mediaMetadata?.extras?.getBoolean("private_media") == true)
            AudioPreviewState(connected=true) else AudioPreviewState(current?.mediaId,player.playWhenReady,identityKeys,
                queueSnapshot,connected=true,currentItem=current,queueGeneration=queueGeneration)
    }
    private fun resetQueue(){queueOwner=null;queueTimeline=null;queueSnapshot=emptyList();queueGeneration++;identityItem=null;identityKeys=emptySet()}
    private suspend fun controller(): MediaController = withContext(Dispatchers.Main.immediate) {
        val pending = future ?: buildAudioControllerAsync(context, object:MediaController.Listener {
                override fun onDisconnected(controller:MediaController) {
                    if(connected===controller) {
                        connected=null;future=null;resetQueue()
                        // Disconnection confirms no attached current item; never route stale metadata.
                        mutableState.value=AudioPreviewState(connected=true)
                    }
                }
            }).also { future = it }
        val controller = suspendCancellableCoroutine<MediaController> { continuation ->
            pending.addListener({
                if (continuation.isActive) try { continuation.resume(pending.get()) }
                catch (e: Exception) { continuation.resumeWithException(e) }
            }, ContextCompat.getMainExecutor(context))
        }
        if (connected !== controller) {
            connected?.removeListener(listener)
            connected = controller
            controller.addListener(listener)
        }
        sync(controller)
        controller
    }
    private data class PreparedQueue(val privacyEpoch:Long,val refs:List<LibraryMediaRef>,val playlist:String?,val contextId:String?,val preserve:Boolean,val items:List<MediaItem>)
    @Volatile private var preparedQueue:PreparedQueue?=null
    // Called on IO. A single immutable entry avoids retaining several huge queues.
    private fun queueItems(refs:List<LibraryMediaRef>,playlistId:String?=null,contextId:String?=null,preserveOccurrences:Boolean=false):List<androidx.media3.common.MediaItem> {
        val epoch=com.domenota.medialoader.data.library.DuplicatePrivacyGuard.generation(context)
        preparedQueue?.takeIf{it.privacyEpoch==epoch&&it.playlist==playlistId&&it.contextId==contextId&&it.preserve==preserveOccurrences&&it.refs==refs}?.let{return it.items}
        val public=refs.filter{!it.item.isPrivate&&!it.item.hidden&&it.item.mediaType==MediaType.AUDIO&&
            it.item.state==DownloadState.COMPLETED&&!it.item.savedUri.isNullOrBlank()}.let { if(preserveOccurrences)it else it.distinctBy { row->row.key } }
        val tag=contextId?:ResumeQueuePolicy.contextId(playlistId,public.map{ListeningHistoryPolicy.keys(it.item,it.aliases)})
        return public.map{AudioPlaybackMetadata.item(it.item,it.aliases,playlistId,tag)}.also{preparedQueue=PreparedQueue(epoch,refs.toList(),playlistId,contextId,preserveOccurrences,it)}
    }
    suspend fun observe() { controller() }
    suspend fun observeCurrent() { controller() }
    suspend fun preview(refs: List<LibraryMediaRef>, requestedId: String, playlistId: String? = null) = update(refs, requestedId, true, playlistId)
    suspend fun open(refs: List<LibraryMediaRef>, requestedId: String, playlistId: String? = null) = update(refs, requestedId, false, playlistId)
    private suspend fun update(refs: List<LibraryMediaRef>, requestedId: String, toggleCurrent: Boolean, playlistId: String?) {
        com.domenota.medialoader.data.storage.HistoricalRecoverySignals.requireIdle()
        val items = withContext(Dispatchers.IO) { queueItems(refs,playlistId) }
        withContext(Dispatchers.Main.immediate) {
            val active = controller()
            com.domenota.medialoader.data.storage.HistoricalRecoverySignals.requireIdle()
            check(if (toggleCurrent) AudioQueuePlayback.preview(active, items, requestedId)
                else AudioQueuePlayback.open(active, items, requestedId)) { "Трек недоступен" }
            sync(active)
        }
    }
    suspend fun playSelection(refs: List<LibraryMediaRef>, shuffle: Boolean, requestedId:String?=null) {
        com.domenota.medialoader.data.storage.HistoricalRecoverySignals.requireIdle()
        val items = withContext(Dispatchers.IO) { queueItems(refs) }
        withContext(Dispatchers.Main.immediate) {
            val active = controller()
            check(AudioQueuePlayback.startSelection(active,items,shuffle,requestedId)) { "Нет доступных треков" }
            sync(active)
        }
    }
    suspend fun resumeSelection(refs:List<LibraryMediaRef>,resolved:ResolvedResumeQueue) {
        com.domenota.medialoader.data.storage.HistoricalRecoverySignals.requireIdle()
        val current=resolved.currentId?:error("Исходный трек недоступен")
        require(refs.map{it.key}==resolved.availableIds && refs.getOrNull(resolved.currentIndex)?.key==current){"Очередь изменилась"}
        val items=withContext(Dispatchers.IO){queueItems(refs,resolved.session.playlistId,resolved.session.id,preserveOccurrences=true)}
        check(items.size==refs.size){"Часть файлов недоступна"}
        val position=ResumeQueuePolicy.clampedPosition(resolved.session,refs[resolved.currentIndex].item.durationMs)
        withContext(Dispatchers.Main.immediate){
            val active=controller()
            check(AudioQueuePlayback.resume(active,items,resolved.currentIndex,resolved.session,position)){"Не удалось восстановить очередь"}
            sync(active)
        }
    }
    suspend fun enqueue(ref: LibraryMediaRef, next: Boolean) {
        com.domenota.medialoader.data.storage.HistoricalRecoverySignals.requireIdle()
        require(!ref.item.isPrivate && !ref.item.hidden && ref.item.mediaType == MediaType.AUDIO &&
            ref.item.state == DownloadState.COMPLETED && !ref.item.savedUri.isNullOrBlank())
        val item = withContext(Dispatchers.IO) { AudioPlaybackMetadata.item(ref.item, ref.aliases) }
        withContext(Dispatchers.Main.immediate) {
            val active = controller()
            com.domenota.medialoader.data.storage.HistoricalRecoverySignals.requireIdle()
            check(AudioQueuePlayback.enqueue(active, item, next)) { "Трек недоступен" }
            sync(active)
        }
    }
    fun close() {
        connected?.removeListener(listener)
        connected = null
        preparedQueue=null
        resetQueue()
        future?.let(MediaController::releaseFuture)
        future = null
    }
}

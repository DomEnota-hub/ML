package com.domenota.medialoader.data.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
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
    val queue: List<androidx.media3.common.MediaItem> = emptyList())

/** One controller owned by the library ViewModel, not by the add-tracks route. */
internal class LibraryAudioPreview(context: Context) {
    private val context = context.applicationContext
    private var future: ListenableFuture<MediaController>? = null
    private var connected: MediaController? = null
    private val mutableState = MutableStateFlow(AudioPreviewState())
    val state = mutableState.asStateFlow()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { sync(player) }
    }
    private fun sync(player: Player) {
        mutableState.value = if (player.currentMediaItem?.mediaMetadata?.extras?.getBoolean("private_media") == true)
            AudioPreviewState() else AudioPreviewState(player.currentMediaItem?.mediaId, player.playWhenReady,
                player.currentMediaItem?.mediaMetadata?.extras?.getStringArrayList(PublicMediaIdentity.EXTRA_KEYS).orEmpty().toSet(),
                (0 until player.mediaItemCount).map { player.getMediaItemAt(it) })
    }
    private suspend fun controller(): MediaController = withContext(Dispatchers.Main.immediate) {
        val pending = future ?: MediaController.Builder(context,
            SessionToken(context, ComponentName(context, AudioPlaybackService::class.java))).buildAsync().also { future = it }
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
    private fun queueItems(refs:List<LibraryMediaRef>,playlistId:String?=null,contextId:String?=null):List<androidx.media3.common.MediaItem> {
        val public=refs.filter{!it.item.isPrivate&&!it.item.hidden&&it.item.mediaType==MediaType.AUDIO&&
            it.item.state==DownloadState.COMPLETED&&!it.item.savedUri.isNullOrBlank()}.distinctBy{it.key}
        val tag=contextId?:ResumeQueuePolicy.contextId(playlistId,public.map{ListeningHistoryPolicy.keys(it.item,it.aliases)})
        return public.map{AudioPlaybackMetadata.item(it.item,it.aliases,playlistId,tag)}
    }
    suspend fun observe() { controller() }
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
    suspend fun playSelection(refs: List<LibraryMediaRef>, shuffle: Boolean) {
        com.domenota.medialoader.data.storage.HistoricalRecoverySignals.requireIdle()
        val items = withContext(Dispatchers.IO) { queueItems(refs) }
        withContext(Dispatchers.Main.immediate) {
            val active = controller()
            check(AudioQueuePlayback.startSelection(active,items,shuffle)) { "Нет доступных треков" }
            sync(active)
        }
    }
    suspend fun resumeSelection(refs:List<LibraryMediaRef>,resolved:ResolvedResumeQueue) {
        com.domenota.medialoader.data.storage.HistoricalRecoverySignals.requireIdle()
        val current=resolved.currentId?:error("Исходный трек недоступен")
        require(refs.map{it.key}==resolved.availableIds && refs.getOrNull(resolved.currentIndex)?.key==current){"Очередь изменилась"}
        val items=withContext(Dispatchers.IO){queueItems(refs,resolved.session.playlistId,resolved.session.id)}
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
        future?.let(MediaController::releaseFuture)
        future = null
    }
}

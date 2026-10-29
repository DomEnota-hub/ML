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
    suspend fun observe() { controller() }
    suspend fun preview(refs: List<LibraryMediaRef>, requestedId: String) = update(refs, requestedId, true)
    suspend fun open(refs: List<LibraryMediaRef>, requestedId: String) = update(refs, requestedId, false)
    private suspend fun update(refs: List<LibraryMediaRef>, requestedId: String, toggleCurrent: Boolean) {
        val items = withContext(Dispatchers.IO) { refs.filter { ref ->
            !ref.item.isPrivate && !ref.item.hidden && ref.item.mediaType == MediaType.AUDIO &&
                ref.item.state == DownloadState.COMPLETED && !ref.item.savedUri.isNullOrBlank()
        }.distinctBy { it.key }.map { AudioPlaybackMetadata.item(it.item, it.aliases) } }
        withContext(Dispatchers.Main.immediate) {
            val active = controller()
            check(if (toggleCurrent) AudioQueuePlayback.preview(active, items, requestedId)
                else AudioQueuePlayback.open(active, items, requestedId)) { "Трек недоступен" }
            sync(active)
        }
    }
    suspend fun enqueue(ref: LibraryMediaRef, next: Boolean) {
        require(!ref.item.isPrivate && !ref.item.hidden && ref.item.mediaType == MediaType.AUDIO &&
            ref.item.state == DownloadState.COMPLETED && !ref.item.savedUri.isNullOrBlank())
        val item = withContext(Dispatchers.IO) { AudioPlaybackMetadata.item(ref.item, ref.aliases) }
        withContext(Dispatchers.Main.immediate) {
            val active = controller()
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

package com.domenota.medialoader.data.playback

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.*
import java.util.Base64

/** Observes the actual service player, not UI taps, and never opens media files. */
internal class ListeningRecorder(context: Context, private val player: Player,
    private val scope: CoroutineScope, private val store: ListeningHistoryStore,
    private val allowed: (MediaItem) -> Boolean) : Player.Listener {
    private val hiddenPrefs = context.applicationContext.getSharedPreferences("music_folder_visibility",Context.MODE_PRIVATE)
    private var active: ListeningCounter? = null
    private var activeKeys: Set<String> = emptySet()
    private var ticker: Job? = null
    private var opening: Job? = null
    private var token = 0L
    private var lastSavedMedia = 0L
    private var lastSavedCredit: Long? = null

    private fun keys(item: MediaItem?): Set<String> {
        if (item == null || !allowed(item)) return emptySet()
        val uri = item.mediaMetadata.extras?.getString(AudioPlaybackMetadata.PUBLIC_URI) ?: return emptySet()
        fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
        val idEncoded = encode(item.mediaId)
        val uriEncoded = encode(uri)
        if (hiddenPrefs.getStringSet("hidden_tracks",emptySet()).orEmpty().any { record ->
                val parts = record.split('.',limit=2)
                parts.size == 2 && (parts[0] == idEncoded || parts[1] == uriEncoded)
            }) return emptySet()
        return item.mediaMetadata.extras?.getStringArrayList(AudioPlaybackMetadata.LISTENING_KEYS).orEmpty().toSet()
    }

    fun attach() { player.addListener(this); refresh() }

    private fun persist(force: Boolean = false) {
        val p = active?.progress ?: return
        if (force || p.creditedAt != lastSavedCredit || p.mediaMs - lastSavedMedia >= 10_000L) {
            store.submit(p)
            lastSavedCredit = p.creditedAt; lastSavedMedia = p.mediaMs
        }
    }

    private fun finish() {
        token++
        opening?.cancel(); opening = null
        active?.close(); persist(force=true)
        active = null; activeKeys = emptySet()
    }

    private fun sample(position: Long = player.currentPosition, duration: Long = player.duration) {
        val counter = active ?: return
        if (counter.progress.generation != store.generation) { finish(); return }
        counter.sample(position.coerceAtLeast(0),SystemClock.elapsedRealtime(),System.currentTimeMillis(),
            player.isPlaying,player.playbackParameters.speed,duration)
        persist()
    }

    private fun begin(currentKeys: Set<String>) {
        if (opening?.isActive == true) return
        val startToken = ++token
        val position = player.currentPosition.coerceAtLeast(0)
        val elapsed = SystemClock.elapsedRealtime()
        val now = System.currentTimeMillis()
        val rate = player.playbackParameters.speed
        val duration = player.duration
        opening = scope.launch {
            try {
                val p = store.begin(currentKeys,position,now) ?: return@launch
                if (startToken != token || keys(player.currentMediaItem).none(currentKeys::contains)) {
                    store.submit(p.copy(closed=true)); return@launch
                }
                if (p.generation != store.generation) return@launch
                activeKeys = currentKeys
                active = ListeningCounter(p,position,elapsed,true,rate,duration)
                lastSavedMedia = p.mediaMs; lastSavedCredit = p.creditedAt
                sample()
                if (!player.isPlaying) persist(force=true)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { com.domenota.medialoader.core.logging.AppLog.w("Listening","Unable to start listening statistics") }
            finally { if (startToken == token) opening = null }
        }
    }

    fun refresh() {
        val current = keys(player.currentMediaItem)
        if (active != null && (activeKeys.none(current::contains) || active?.progress?.generation != store.generation)) finish()
        if (current.isEmpty()) { finish(); ticker?.cancel(); ticker = null; return }
        if (active != null) sample()
        if (player.playbackState == Player.STATE_ENDED) {
            finish(); ticker?.cancel(); ticker = null; return
        }
        if (player.isPlaying) {
            if (active == null) begin(current)
            if (ticker?.isActive != true) ticker = scope.launch {
                while (isActive && player.isPlaying) { delay(1000L); refresh() }
            }
        } else {
            persist(force=true)
            ticker?.cancel(); ticker = null
        }
    }

    override fun onEvents(player: Player, events: Player.Events) { refresh() }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT || reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
            activeKeys.isNotEmpty() && activeKeys.none(keys(mediaItem)::contains)) finish()
        else if (active == null && opening?.isActive == true) finish()
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (active == null) { if (opening?.isActive == true) finish(); return }
        val oldKeys = keys(oldPosition.mediaItem)
        val newKeys = keys(newPosition.mediaItem)
        if (oldKeys.any(activeKeys::contains)) sample(oldPosition.positionMs,-1L)
        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION || activeKeys.none(newKeys::contains)) finish()
        else active?.reset(newPosition.positionMs,SystemClock.elapsedRealtime(),player.isPlaying,player.playbackParameters.speed)
    }

    fun release() {
        player.removeListener(this)
        if (activeKeys.any(keys(player.currentMediaItem)::contains)) sample()
        finish()
        ticker?.cancel(); ticker = null
    }
}

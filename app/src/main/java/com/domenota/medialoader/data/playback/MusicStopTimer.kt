package com.domenota.medialoader.data.playback

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

internal enum class MusicStopCriterion(val label: String) {
    AT_TIME("В указанное время"), AFTER_TIME("Через указанное время"), TRACKS("После окончания треков"),
    PLAYLIST("После окончания плейлиста"), QUEUE("После окончания очереди")
}
internal data class MusicStopRequest(val criterion: MusicStopCriterion, val value: Long = 0, val finishTrack: Boolean = false)
internal data class MusicStopStatus(val request: MusicStopRequest? = null, val remaining: Long = 0, val waiting: Boolean = false,
    val playlistAvailable: Boolean = false, val message: String? = null)

internal object MusicStopTimerPolicy {
    fun nextTime(now: Long, minuteOfDay: Int, zone: ZoneId): Long {
        require(minuteOfDay in 0..1439)
        val local = Instant.ofEpochMilli(now).atZone(zone)
        val time = LocalTime.of(minuteOfDay / 60,minuteOfDay % 60)
        var candidate = local.toLocalDate().atTime(time).atZone(zone)
        if (!candidate.toInstant().isAfter(local.toInstant())) candidate = local.toLocalDate().plusDays(1).atTime(time).atZone(zone)
        return candidate.toInstant().toEpochMilli()
    }
    fun valid(request: MusicStopRequest): Boolean = when (request.criterion) {
        MusicStopCriterion.AT_TIME -> request.value in 0L..1439L
        MusicStopCriterion.AFTER_TIME -> request.value in 1L..86399L
        MusicStopCriterion.TRACKS -> request.value in 1L..999L
        else -> true
    }
}

/** All mutations and callbacks run on the service's player looper. No Activity lifetime dependency. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MusicStopTimer(private val player: ExoPlayer, private val scope: CoroutineScope) : Player.Listener {
    private val mutable = MutableStateFlow(MusicStopStatus())
    val state = mutable.asStateFlow()
    private var request: MusicStopRequest? = null
    private var deadline = 0L
    private var tracksLeft = 0L
    private var waiting = false
    private var playlistId: String? = null
    private var repeatBefore: Int? = null
    private var changingRepeat = false
    private var ticker: Job? = null
    var stopGeneration = 0L
        private set
    private fun currentPlaylist() = player.currentMediaItem?.mediaMetadata?.extras?.getString(AudioPlaybackMetadata.PLAYLIST_ID)
    private fun publicCurrent() = player.currentMediaItem != null && player.currentMediaItem?.mediaMetadata?.extras?.getBoolean("private_media") != true
    private fun publish(message: String? = null) {
        val active = request
        val remaining = when (active?.criterion) {
            MusicStopCriterion.AT_TIME -> ((deadline-System.currentTimeMillis()+999)/1000).coerceAtLeast(0)
            MusicStopCriterion.AFTER_TIME -> ((deadline-SystemClock.elapsedRealtime()+999)/1000).coerceAtLeast(0)
            MusicStopCriterion.TRACKS -> tracksLeft
            else -> 0L
        }
        mutable.value = MusicStopStatus(active,remaining,waiting,publicCurrent() && currentPlaylist()!=null,message)
    }
    fun attach() { player.addListener(this); publish() }
    fun start(value: MusicStopRequest): Boolean {
        if (!MusicStopTimerPolicy.valid(value) || !publicCurrent() || value.criterion == MusicStopCriterion.PLAYLIST && currentPlaylist()==null) return false
        cancel()
        request = value; waiting = false; tracksLeft = value.value; playlistId = currentPlaylist()
        deadline = when (value.criterion) {
            MusicStopCriterion.AT_TIME -> MusicStopTimerPolicy.nextTime(System.currentTimeMillis(),value.value.toInt(),ZoneId.systemDefault())
            MusicStopCriterion.AFTER_TIME -> SystemClock.elapsedRealtime()+value.value*1000L
            else -> 0L
        }
        if (value.criterion == MusicStopCriterion.PLAYLIST || value.criterion == MusicStopCriterion.QUEUE) {
            repeatBefore = player.repeatMode
            changingRepeat = true
            try { player.repeatMode = Player.REPEAT_MODE_OFF } finally { changingRepeat = false }
        }
        updateBoundary(); publish()
        ticker = scope.launch {
            while (request != null) {
                val r = request ?: break
                val expired = when (r.criterion) {
                    MusicStopCriterion.AT_TIME -> System.currentTimeMillis() >= deadline
                    MusicStopCriterion.AFTER_TIME -> SystemClock.elapsedRealtime() >= deadline
                    else -> false
                }
                if (expired && !waiting) {
                    if (r.finishTrack && player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE) {
                        waiting = true; updateBoundary()
                    } else { stop(); break }
                }
                publish()
                delay(250)
            }
        }
        return true
    }
    private fun isLastPlaylistItem(): Boolean {
        val id = playlistId ?: return false
        val timeline = player.currentTimeline
        if (timeline.isEmpty || currentPlaylist()!=id) return false
        var index = timeline.getNextWindowIndex(player.currentMediaItemIndex,Player.REPEAT_MODE_OFF,player.shuffleModeEnabled)
        while (index != androidx.media3.common.C.INDEX_UNSET) {
            if (player.getMediaItemAt(index).mediaMetadata.extras?.getString(AudioPlaybackMetadata.PLAYLIST_ID) == id) return false
            index = timeline.getNextWindowIndex(index,Player.REPEAT_MODE_OFF,player.shuffleModeEnabled)
        }
        return true
    }
    private fun updateBoundary() {
        val r = request
        val atEnd = r != null && when (r.criterion) {
            MusicStopCriterion.TRACKS -> tracksLeft <= 1
            MusicStopCriterion.PLAYLIST -> isLastPlaylistItem()
            MusicStopCriterion.QUEUE -> !player.hasNextMediaItem()
            else -> waiting
        }
        player.setPauseAtEndOfMediaItems(atEnd)
    }
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && request != null) stop()
    }
    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
        val r = request
        if (r == null) { publish(); return }
        if (!publicCurrent()) { cancel("Условие остановки отменено при смене режима"); return }
        if (waiting) { stop(); return }
        if (r.criterion == MusicStopCriterion.PLAYLIST && currentPlaylist()!=playlistId) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // A queued extra can precede more playlist items; keep the original scope.
                val hasMore = (0 until player.mediaItemCount).any { index ->
                    player.getMediaItemAt(index).mediaMetadata.extras?.getString(AudioPlaybackMetadata.PLAYLIST_ID)==playlistId
                }
                if (!hasMore) { stop(); return }
            } else { cancel("Плейлист изменён"); return }
        }
        if (r.criterion == MusicStopCriterion.TRACKS &&
            (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)) {
            tracksLeft--
            if (tracksLeft <= 0) { stop(); return }
        }
        updateBoundary(); publish()
    }
    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED && request != null) stop() else publish()
    }
    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
        if (request != null && player.mediaItemCount==0) cancel("Очередь завершена")
        else { updateBoundary(); publish() }
    }
    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { updateBoundary(); publish() }
    override fun onRepeatModeChanged(repeatMode: Int) {
        if (!changingRepeat && repeatBefore != null && repeatMode != Player.REPEAT_MODE_OFF) {
            // An explicit change wins over a scheduled end-of-list operation.
            repeatBefore = null; cancel("Таймер отменён: изменён повтор")
        }
    }
    private fun stop() {
        stopGeneration++
        player.pause()
        cancel("Воспроизведение остановлено")
    }
    fun cancel(message: String? = null) {
        request = null; waiting = false
        ticker?.cancel(); ticker = null
        player.setPauseAtEndOfMediaItems(false)
        val previous = repeatBefore; repeatBefore = null
        if (previous != null && player.repeatMode == Player.REPEAT_MODE_OFF) {
            changingRepeat = true
            try { player.repeatMode = previous } finally { changingRepeat = false }
        }
        publish(message)
    }
    fun release() { cancel(); player.removeListener(this) }
}

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
    AT_TIME("В указанное время"), AFTER_TIME("Через указанное время"),
    PLAYLIST("После окончания плейлиста")
}
internal data class MusicStopRequest(val criterion: MusicStopCriterion, val value: Long = 0, val finishTrack: Boolean = false)
internal data class MusicStopStatus(val request: MusicStopRequest? = null, val remaining: Long = 0, val waiting: Boolean = false,
    val playlistAvailable: Boolean = false, val message: String? = null,
    val playlistUnavailableReason: String? = null)

internal object MusicStopTimerPolicy {
    fun nextTime(now: Long, minuteOfDay: Int, zone: ZoneId): Long {
        require(minuteOfDay in 0..1439)
        val local = Instant.ofEpochMilli(now).atZone(zone)
        val time = LocalTime.of(minuteOfDay / 60,minuteOfDay % 60)
        var candidate = local.toLocalDate().atTime(time).atZone(zone)
        if (!candidate.toInstant().isAfter(local.toInstant())) candidate = local.toLocalDate().plusDays(1).atTime(time).atZone(zone)
        return candidate.toInstant().toEpochMilli()
    }
    fun durationSeconds(hours: String, minutes: String, seconds: String): Long {
        val h = hours.ifEmpty { "0" }.toLongOrNull() ?: return -1
        val m = minutes.ifEmpty { "0" }.toLongOrNull() ?: return -1
        val s = seconds.ifEmpty { "0" }.toLongOrNull() ?: return -1
        return if (h in 0..23 && m in 0..59 && s in 0..59) h*3600+m*60+s else -1
    }
    fun playlistAvailable(publicCurrent: Boolean, playlistId: String?, repeatMode: Int): Boolean =
        publicCurrent && playlistId != null && repeatMode != Player.REPEAT_MODE_ONE

    fun valid(request: MusicStopRequest): Boolean = when (request.criterion) {
        MusicStopCriterion.AT_TIME -> request.value in 0L..1439L
        MusicStopCriterion.AFTER_TIME -> request.value in 1L..86399L
        MusicStopCriterion.PLAYLIST -> true
    }
}

/** All mutations and callbacks run on the service's player looper. No Activity lifetime dependency. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MusicStopTimer(private val player: ExoPlayer, private val scope: CoroutineScope) : Player.Listener {
    private val mutable = MutableStateFlow(MusicStopStatus())
    val state = mutable.asStateFlow()
    private var request: MusicStopRequest? = null
    private var deadline = 0L
    private var waiting = false
    private var playlistId: String? = null
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
            else -> 0L
        }
        val available = MusicStopTimerPolicy.playlistAvailable(publicCurrent(), currentPlaylist(), player.repeatMode)
        val unavailableReason = when {
            !publicCurrent() || currentPlaylist() == null -> "Нет активного плейлиста"
            player.repeatMode == Player.REPEAT_MODE_ONE -> "Включён повтор трека"
            else -> null
        }
        mutable.value = MusicStopStatus(active,remaining,waiting,available,message,unavailableReason)
    }
    fun attach() { player.addListener(this); publish() }
    fun start(value: MusicStopRequest): Boolean {
        if (!MusicStopTimerPolicy.valid(value) || !publicCurrent() || value.criterion == MusicStopCriterion.PLAYLIST && !MusicStopTimerPolicy.playlistAvailable(publicCurrent(), currentPlaylist(), player.repeatMode)) return false
        cancel()
        request = value; waiting = false; playlistId = currentPlaylist()
        deadline = when (value.criterion) {
            MusicStopCriterion.AT_TIME -> MusicStopTimerPolicy.nextTime(System.currentTimeMillis(),value.value.toInt(),ZoneId.systemDefault())
            MusicStopCriterion.AFTER_TIME -> SystemClock.elapsedRealtime()+value.value*1000L
            else -> 0L
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
            MusicStopCriterion.PLAYLIST -> isLastPlaylistItem()
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
        if (request?.criterion == MusicStopCriterion.PLAYLIST && repeatMode == Player.REPEAT_MODE_ONE) {
            cancel("Таймер отменён: включён повтор трека")
        } else { updateBoundary(); publish() }
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
        publish(message)
    }
    fun release() { cancel(); player.removeListener(this) }
}

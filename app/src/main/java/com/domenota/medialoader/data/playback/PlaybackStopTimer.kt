package com.domenota.medialoader.data.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import java.time.ZonedDateTime
import java.time.LocalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class StopCriterion { TIME, DURATION, TRACKS, PLAYLIST, QUEUE }
internal data class StopRequest(val criterion: StopCriterion, val seconds: Long = 0,
    val hour: Int = 0, val minute: Int = 0, val tracks: Int = 1, val finishTrack: Boolean = false)
internal data class StopTimerState(val criterion: StopCriterion, val remaining: Long = 0, val waitingForEnd: Boolean = false)
internal object StopTimerPolicy {
    fun nextTime(now: ZonedDateTime, hour: Int, minute: Int): Long {
        require(hour in 0..23 && minute in 0..59)
        val today = now.toLocalDate().atTime(LocalTime.of(hour,minute)).atZone(now.zone)
        return (if (today.isAfter(now)) today else today.plusDays(1)).toInstant().toEpochMilli()
    }
    fun countAfterTransition(remaining: Long, reason: Int): Long =
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
            (remaining-1).coerceAtLeast(0) else remaining
}

/** Only the service owns the timer. All mutations are on the player main thread. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackStopTimer(private val player: ExoPlayer, private val onStopped: () -> Unit) : Player.Listener {
    private val mutable = MutableStateFlow<StopTimerState?>(null)
    val state = mutable.asStateFlow()
    private var request: StopRequest? = null
    private var deadline = 0L
    private var remainingTracks = 0L
    private var playlistId: String? = null
    private var savedRepeat: Int? = null
    private var adjustingRepeat = false
    private var waiting = false
    init { player.addListener(this) }
    private fun tag(item: MediaItem?) = item?.mediaMetadata?.extras?.getString(AudioPlaybackMetadata.PLAYLIST_ID)
    fun start(next: StopRequest): Boolean {
        if (player.currentMediaItem == null || player.currentMediaItem?.mediaMetadata?.extras?.getBoolean("private_media") == true) return false
        if (next.criterion == StopCriterion.PLAYLIST && tag(player.currentMediaItem) == null) return false
        if (next.criterion == StopCriterion.DURATION && next.seconds !in 1..86399) return false
        if (next.criterion == StopCriterion.TRACKS && next.tracks !in 1..999) return false
        if (next.criterion == StopCriterion.TIME && (next.hour !in 0..23 || next.minute !in 0..59)) return false
        cancel()
        request = next
        deadline = when(next.criterion) {
            StopCriterion.TIME -> StopTimerPolicy.nextTime(ZonedDateTime.now(),next.hour,next.minute)
            StopCriterion.DURATION -> SystemClock.elapsedRealtime()+next.seconds*1000
            else -> 0
        }
        remainingTracks = next.tracks.toLong()
        playlistId = if (next.criterion == StopCriterion.PLAYLIST) tag(player.currentMediaItem) else null
        if (next.criterion == StopCriterion.QUEUE || next.criterion == StopCriterion.PLAYLIST) {
            savedRepeat = player.repeatMode; enforceRepeatOff()
        }
        refreshBoundary(); tick(); return true
    }
    private fun enforceRepeatOff() {
        if (player.repeatMode != Player.REPEAT_MODE_OFF) {
            adjustingRepeat = true; player.repeatMode = Player.REPEAT_MODE_OFF; adjustingRepeat = false
        }
    }
    fun cancel() {
        request = null; waiting = false; playlistId = null
        player.setPauseAtEndOfMediaItems(false)
        val repeat = savedRepeat; savedRepeat = null
        if (repeat != null) { adjustingRepeat = true; player.repeatMode = repeat; adjustingRepeat = false }
        mutable.value = null
    }
    private fun stop() {
        if (request == null) return
        request = null
        player.pause()
        onStopped(); cancel()
    }
    private fun hasLaterPlaylistTrack(): Boolean {
        val id = playlistId ?: return false
        val timeline = player.currentTimeline
        if (timeline.isEmpty || player.currentMediaItemIndex !in 0 until timeline.windowCount) return false
        var index = player.currentMediaItemIndex
        repeat(timeline.windowCount) {
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF,player.shuffleModeEnabled)
            if (index == androidx.media3.common.C.INDEX_UNSET) return false
            if (tag(player.getMediaItemAt(index)) == id) return true
        }
        return false
    }
    private fun refreshBoundary() {
        val r = request ?: return
        if (player.currentMediaItem == null || player.currentMediaItem?.mediaMetadata?.extras?.getBoolean("private_media") == true) { cancel(); return }
        player.setPauseAtEndOfMediaItems(when (r.criterion) {
            StopCriterion.TRACKS -> remainingTracks <= 1
            StopCriterion.PLAYLIST -> tag(player.currentMediaItem) == playlistId && !hasLaterPlaylistTrack()
            StopCriterion.QUEUE -> !player.hasNextMediaItem()
            else -> waiting
        })
    }
    fun tick() {
        val r = request ?: return
        if (player.currentMediaItem == null) { cancel(); return }
        val left = when(r.criterion) {
            StopCriterion.TIME -> (deadline-System.currentTimeMillis()).coerceAtLeast(0)
            StopCriterion.DURATION -> (deadline-SystemClock.elapsedRealtime()).coerceAtLeast(0)
            StopCriterion.TRACKS -> remainingTracks
            else -> 0L
        }
        if ((r.criterion == StopCriterion.TIME || r.criterion == StopCriterion.DURATION) && left == 0L && !waiting) {
            if (!r.finishTrack || !player.playWhenReady || player.playbackState == Player.STATE_ENDED) { stop(); return }
            waiting = true; refreshBoundary()
        }
        mutable.value = StopTimerState(r.criterion, if (r.criterion == StopCriterion.TIME || r.criterion == StopCriterion.DURATION) (left+999)/1000 else left,waiting)
    }
    override fun onRepeatModeChanged(repeatMode: Int) {
        if (!adjustingRepeat && savedRepeat != null && repeatMode != Player.REPEAT_MODE_OFF) {
            savedRepeat = repeatMode; enforceRepeatOff()
        }
    }
    override fun onTimelineChanged(timeline: Timeline, reason: Int) { refreshBoundary() }
    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { refreshBoundary() }
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val r = request ?: return
        if (r.criterion == StopCriterion.TRACKS) {
            remainingTracks = StopTimerPolicy.countAfterTransition(remainingTracks,reason)
            if (remainingTracks == 0L) { stop(); return }
        }
        if (r.criterion == StopCriterion.PLAYLIST &&
            (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) &&
            tag(mediaItem) != playlistId) { cancel(); return }
        refreshBoundary(); tick()
    }
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && request != null) stop()
        else if (!playWhenReady && waiting) stop()
    }
    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED && request != null) stop()
    }
    fun release() { cancel(); player.removeListener(this) }
}

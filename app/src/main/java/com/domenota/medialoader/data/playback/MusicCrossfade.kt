package com.domenota.medialoader.data.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The primary deck remains the sole session, queue, focus and recorder owner.
 * The temporary deck only overlaps a verified public next item. Any interruption
 * releases it before restoring primary volume; never put it in the media session.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MusicCrossfade(
    private val context: Context,
    private val primary: ExoPlayer,
    private val scope: CoroutineScope,
    private val preferences: CrossfadePreferences,
    private val equalizer: EqualizerSettingsRepository,
    private val allowPublic: (MediaItem) -> Boolean,
    private val normalizationGain: (MediaItem) -> Double,
    private val timerAllows: (MediaItem, Long) -> Boolean,
) : Player.Listener {
    private data class Deck(
        val player: ExoPlayer, val eq: EqualizerAudioProcessor,
        val from: MediaItem, val to: MediaItem, val fromIndex: Int, val toIndex: Int,
        val duration: Long, val settings: CrossfadeSettings, val speed: Float,
        val volume: Float, var started: Boolean = false, var startRemaining: Long = 0L,
    )
    private var deck: Deck? = null
    private var ticker: Job? = null
    private var failedFrom: Pair<Int, MediaItem>? = null
    private fun matches(a: MediaItem?, b: MediaItem): Boolean =
        a != null && AudioQueuePlayback.sameQueueItem(a, b)
    private fun nextIndex(): Int {
        val timeline = primary.currentTimeline
        return if (timeline.isEmpty) C.INDEX_UNSET else timeline.getNextWindowIndex(
            primary.currentMediaItemIndex, primary.repeatMode, primary.shuffleModeEnabled)
    }
    private fun localPublic(item: MediaItem): Boolean =
        allowPublic(item) && !PrivatePlaybackPolicy.isPrivate(item) &&
            item.localConfiguration?.uri?.scheme in setOf("file", "content")
    private fun valid(d: Deck, remaining: Long): Boolean =
        preferences.state.value == d.settings && primary.playbackParameters.speed == d.speed &&
            primary.isPlaying && primary.currentMediaItemIndex == d.fromIndex &&
            matches(primary.currentMediaItem, d.from) && nextIndex() == d.toIndex &&
            d.toIndex in 0 until primary.mediaItemCount &&
            matches(primary.getMediaItemAt(d.toIndex), d.to) &&
            localPublic(d.from) && localPublic(d.to) &&
            timerAllows(d.to, (remaining / d.speed).toLong())

    fun attach() {
        primary.addListener(this)
        ticker = scope.launch {
            while (true) {
                tick()
                delay(if (deck == null) 150L else 25L)
            }
        }
    }

    private fun tick() {
        val settings = preferences.state.value
        val current = primary.currentMediaItem
        val speed = primary.playbackParameters.speed
        val remaining = primary.duration - primary.currentPosition
        val active = deck
        if (active != null) {
            if (!valid(active, remaining) || active.player.playerError != null ||
                active.player.playbackState == Player.STATE_ENDED) {
                if (active.player.playerError != null || active.player.playbackState == Player.STATE_ENDED)
                    failedFrom = active.fromIndex to active.from
                cancel()
                return
            }
            active.eq.updateSettings(equalizer.state.value.effective(EqualizerTrack.fromMediaItem(active.to)))
            active.eq.updateNormalizationGain(normalizationGain(active.to))
            if (!active.started && remaining <= active.duration &&
                active.player.playbackState == Player.STATE_READY) {
                // If preparation was late, shorten the overlap; don't truncate the next song.
                if (remaining < 350 * speed || active.player.duration <= remaining + 500) {
                    failedFrom = active.fromIndex to active.from
                    cancel()
                    return
                }
                active.startRemaining = remaining
                active.started = true
                active.player.play()
            }
            if (active.started) {
                if (!active.player.isPlaying) {
                    primary.volume = active.volume
                    active.player.volume = 0f
                    return
                }
                val (out, incoming) = CrossfadePolicy.gains(remaining, active.startRemaining)
                primary.volume = active.volume * out
                active.player.volume = active.volume * incoming
            }
            return
        }
        if (!settings.enabled || !primary.isPlaying || current == null || !localPublic(current)) return
        val index = primary.currentMediaItemIndex
        if (failedFrom?.let { it.first == index && matches(current, it.second) } == true) return
        val duration = CrossfadePolicy.durationMs(settings, primary.duration, speed)
        if (duration == 0L || remaining <= 350 * speed || remaining > duration + (1500 * speed).toLong()) return
        val next = nextIndex()
        if (next !in 0 until primary.mediaItemCount) return
        val item = primary.getMediaItemAt(next)
        if (!localPublic(item) || !timerAllows(item, (remaining / speed).toLong())) return
        val processor = EqualizerAudioProcessor(publishFormat = false)
        processor.updateSettings(equalizer.state.value.effective(EqualizerTrack.fromMediaItem(item)))
        processor.updateNormalizationGain(normalizationGain(item))
        var temporary: ExoPlayer? = null
        try {
            val p = ExoPlayer.Builder(context, EqualizerRenderersFactory(context, processor))
                .setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA).build(), false)
                .build()
            temporary = p
            p.volume = 0f
            p.playbackParameters = primary.playbackParameters
            deck = Deck(p, processor, current, item, index, next, duration, settings, speed, primary.volume)
            p.setMediaItem(item)
            p.prepare()
        } catch (_: Exception) {
            if (deck != null) cancel() else temporary?.release()
            failedFrom = index to current
        }
    }

    /** Called synchronously on privacy/queue mutations; no private alias is retained by a second deck. */
    fun recheckAccess() {
        val d = deck ?: return
        if (!localPublic(d.from) || !localPublic(d.to)) cancel()
    }

    private fun cancel() {
        val d = deck ?: return
        deck = null
        try {
            d.player.volume = 0f
            d.player.pause()
            d.player.release()
        } finally { primary.volume = d.volume }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        failedFrom = null
        val d = deck ?: return
        // Only a natural boundary can hand off the already audible prefix.
        val natural = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        if (natural && d.started && d.player.isPlaying && primary.playWhenReady &&
            primary.currentMediaItemIndex == d.toIndex && matches(mediaItem, d.to) &&
            localPublic(d.to) && preferences.state.value == d.settings) {
            val position = d.player.currentPosition.coerceAtLeast(0L)
            // Clear ownership first: the seek emits a synchronous discontinuity callback.
            cancel()
            if (position > 0 && (primary.duration <= 0 || position < primary.duration)) primary.seekTo(position)
        } else cancel()
    }
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
            cancel()
            failedFrom = null
        }
    }
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (!playWhenReady) cancel() }
    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
        if (playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) cancel()
    }
    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) cancel()
    }
    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) { cancel(); failedFrom = null }
        else recheckAccess()
    }
    override fun onRepeatModeChanged(repeatMode: Int) { cancel(); failedFrom = null }
    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { cancel(); failedFrom = null }
    override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
        cancel(); failedFrom = null
    }
    override fun onPlayerError(error: androidx.media3.common.PlaybackException) { cancel() }
    fun release() { ticker?.cancel(); ticker = null; primary.removeListener(this); cancel() }
}

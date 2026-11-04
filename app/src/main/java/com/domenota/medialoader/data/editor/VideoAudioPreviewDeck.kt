package com.domenota.medialoader.data.editor

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

/**
 * Audio-only players follow the editor's video playhead. Each track keeps its
 * independent source trim, timeline placement, volume and fade envelope.
 * No playback state is written to the downloaded source.
 */
internal class VideoAudioPreviewDeck(private val context: Context) {
    private data class Preview(val sourceUri: String, val player: ExoPlayer,
        var lastProjectMs: Long? = null, var lastTrack: VideoAudioTrackDraft? = null,
        var lastAudible: Boolean = false, var lastSampleAt: Long = 0L, var lastPlaying: Boolean = false,
        var lastDriftReportAt: Long = 0L, var lastSeekAt: Long = Long.MIN_VALUE / 2,
        var playingSinceMs: Long? = null)
    private val active = mutableMapOf<String, Preview>()

    fun synchronize(project: VideoProjectDraft?, positionMs: Long, playing: Boolean, embeddedTrackIds: Set<String> = emptySet(),
        masterBuffering: Boolean = false) {
        val tracks = project?.visibleAudioTracks().orEmpty().filterNot { it.id in embeddedTrackIds }
        val valid = tracks.map { it.id }.toSet()
        active.keys.filterNot { it in valid }.forEach { key ->
            active.remove(key)?.player?.release()
        }
        for (track in tracks) {
            val existing = active[track.id]
            val preview = if (existing != null && existing.sourceUri == track.sourceUri)
                existing else {
                existing?.player?.release()
                val player = ExoPlayer.Builder(context).build().apply {
                    // Detached audio still points to the original video file. Disable its
                    // video decoder before preparation so it cannot compete with preview.
                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                    setMediaItem(MediaItem.fromUri(Uri.parse(track.sourceUri)),
                        track.sourceAt(positionMs - track.atMs))
                    setPlaybackSpeed(track.speed)
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            com.domenota.medialoader.core.logging.AppLog.e("AudioPreview", "Player failure · code=${error.errorCodeName}", error)
                        }
                    })
                    prepare()
                    playWhenReady = false
                }
                Preview(track.sourceUri, player).also { active[track.id] = it }
            }
            val player = preview.player
            val sourcePosition = VideoAudioSyncDecision.sourcePositionMs(track, positionMs)
            val audible = sourcePosition != null
            if (sourcePosition != null) {
                val now = android.os.SystemClock.elapsedRealtime()
                val jump = VideoAudioSyncDecision.isJump(track, positionMs, preview.lastTrack,
                    preview.lastProjectMs, preview.lastAudible, playing, preview.lastPlaying,
                    now - preview.lastSampleAt)
                val ready = player.playbackState == Player.STATE_READY
                if (!playing || !player.isPlaying) preview.playingSinceMs = null
                else if (preview.playingSinceMs == null) preview.playingSinceMs = now
                val continuousPlaybackMs = preview.playingSinceMs?.let { now - it } ?: 0L
                val drift = player.currentPosition - sourcePosition
                if (!jump && playing && ready &&
                    abs(drift) > 500L && now - preview.lastDriftReportAt >= 2000L) {
                    preview.lastDriftReportAt = now
                    com.domenota.medialoader.core.logging.AppLog.w("AudioSync",
                        "Drift correction · projectMs=$positionMs · expectedSourceMs=$sourcePosition · actualSourceMs=${player.currentPosition}")
                }
                val didSeek = VideoAudioSyncDecision.shouldSeek(jump, ready,
                    playing, drift, now - preview.lastSeekAt, continuousPlaybackMs,
                    masterBuffering, track.speed)
                if (didSeek) {
                    preview.lastSeekAt = now
                    preview.playingSinceMs = null
                    com.domenota.medialoader.core.logging.AppLog.verboseSampled("AudioSync", "seek:${track.id}",
                        "Audio seek · jump=$jump · driftMs=$drift · playing=$playing")
                    player.seekTo(sourcePosition)
                }
                val speed = VideoAudioSyncDecision.playbackSpeed(track.speed, drift,
                    playing && ready && !jump && !didSeek, player.playbackParameters.speed)
                if (kotlin.math.abs(player.playbackParameters.speed - speed) > .001f)
                    player.setPlaybackSpeed(speed)
                player.volume = track.gainAt(positionMs).coerceIn(0f, 1f)
                player.playWhenReady = playing
            } else {
                player.playWhenReady = false
                preview.playingSinceMs = null
            }
            preview.lastProjectMs = positionMs
            preview.lastAudible = audible
            preview.lastTrack = track
            preview.lastSampleAt = android.os.SystemClock.elapsedRealtime()
            preview.lastPlaying = playing
        }
    }

    fun pause() = active.values.forEach {
        it.player.pause()
        it.playingSinceMs = null
        it.lastPlaying = false
    }

    fun release() {
        active.values.forEach { it.player.release() }
        active.clear()
    }
}

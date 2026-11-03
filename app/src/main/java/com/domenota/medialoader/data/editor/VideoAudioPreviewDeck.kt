package com.domenota.medialoader.data.editor

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
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
        var lastDriftReportAt: Long = 0L)
    private val active = mutableMapOf<String, Preview>()

    fun synchronize(project: VideoProjectDraft?, positionMs: Long, playing: Boolean, embeddedTrackIds: Set<String> = emptySet()) {
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
                    setMediaItem(MediaItem.fromUri(Uri.parse(track.sourceUri)))
                    addListener(object : androidx.media3.common.Player.Listener {
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
            val localTime = positionMs - track.atMs
            val audible = localTime >= 0L && localTime < track.durationMs
            if (audible) {
                val sourcePosition = track.sourceAt(localTime)
                if (player.playbackParameters.speed != track.speed) player.setPlaybackSpeed(track.speed)
                val previous = preview.lastTrack
                val jump = preview.lastProjectMs == null || abs(positionMs - preview.lastProjectMs!!) > 250L ||
                    previous == null || previous.inMs != track.inMs || previous.outMs != track.outMs ||
                    previous.atMs != track.atMs || previous.speed != track.speed
                val drift = player.currentPosition - sourcePosition
                val now = android.os.SystemClock.elapsedRealtime()
                if (!jump && playing && player.playbackState == androidx.media3.common.Player.STATE_READY &&
                    abs(drift) > 500L && now - preview.lastDriftReportAt >= 2000L) {
                    preview.lastDriftReportAt = now
                    com.domenota.medialoader.core.logging.AppLog.w("AudioSync",
                        "Drift correction · projectMs=$positionMs · expectedSourceMs=$sourcePosition · actualSourceMs=${player.currentPosition}")
                }
                if (jump || (player.playbackState == androidx.media3.common.Player.STATE_READY &&
                        abs(player.currentPosition - sourcePosition) > if (playing) 180L else 20L)) {
                    player.playWhenReady = false
                    player.seekTo(sourcePosition)
                }
                player.volume = track.gainAt(positionMs).coerceIn(0f, 1f)
                player.playWhenReady = playing
            } else player.playWhenReady = false
            preview.lastProjectMs = positionMs
            preview.lastTrack = track
        }
    }

    fun pause() = active.values.forEach { it.player.pause() }

    fun release() {
        active.values.forEach { it.player.release() }
        active.clear()
    }
}

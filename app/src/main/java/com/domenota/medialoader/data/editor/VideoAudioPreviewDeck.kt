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
    private data class Preview(val sourceUri: String, val player: ExoPlayer)
    private val active = mutableMapOf<String, Preview>()

    fun synchronize(project: VideoProjectDraft?, positionMs: Long, playing: Boolean) {
        val tracks = project?.visibleAudioTracks().orEmpty()
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
                    prepare()
                    playWhenReady = false
                }
                Preview(track.sourceUri, player).also { active[track.id] = it }
            }
            val player = preview.player
            val localTime = positionMs - track.atMs
            val audible = localTime >= 0L && localTime < track.durationMs
            if (audible) {
                val sourcePosition = track.inMs + localTime
                if (abs(player.currentPosition - sourcePosition) > 250L)
                    player.seekTo(sourcePosition)
                player.volume = track.gainAt(positionMs).coerceIn(0f, 1f)
                if (playing && !player.isPlaying) player.play()
                if (!playing && player.isPlaying) player.pause()
            } else if (player.isPlaying) player.pause()
        }
    }

    fun pause() = active.values.forEach { it.player.pause() }

    fun release() {
        active.values.forEach { it.player.release() }
        active.clear()
    }
}

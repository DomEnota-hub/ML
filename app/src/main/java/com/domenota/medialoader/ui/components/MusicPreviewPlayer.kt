package com.domenota.medialoader.ui.components

import android.webkit.WebSettings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.domenota.medialoader.core.logging.AppLog

/** Streams one search result at a time without creating a download or history row. */
internal class MusicPreviewPlayer(private val player: ExoPlayer) {
    var currentUrl by mutableStateOf<String?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var durationMs by mutableStateOf(0L)
        private set

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        override fun onPlaybackStateChanged(playbackState: Int) {
            buffering = playbackState == Player.STATE_BUFFERING
            durationMs = player.duration.takeIf { it > 0L } ?: 0L
        }
        override fun onPlayerError(cause: PlaybackException) {
            playing = false
            buffering = false
            error = "Источник не разрешил прослушивание. Файл можно скачать."
            AppLog.w("MusicPreview", "Stream failed · code=${cause.errorCode}")
        }
    }

    init { player.addListener(listener) }

    fun toggle(url: String) {
        error = null
        if (currentUrl == url) {
            if (player.isPlaying) player.pause()
            else {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
            }
            return
        }
        currentUrl = url
        positionMs = 0L
        durationMs = 0L
        buffering = true
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()
    }

    fun stop() {
        player.stop()
        currentUrl = null
        playing = false
        buffering = false
        error = null
        positionMs = 0L
        durationMs = 0L
    }

    fun seekTo(position: Long) {
        if (durationMs > 0L) player.seekTo(position.coerceIn(0L, durationMs))
        positionMs = player.currentPosition.coerceAtLeast(0L)
    }

    fun updatePosition() {
        if (currentUrl != null) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.takeIf { it > 0L } ?: durationMs
        }
    }

    fun release() {
        player.removeListener(listener)
        player.release()
    }
}

@Composable
internal fun rememberMusicPreviewPlayer(): MusicPreviewPlayer {
    val context = LocalContext.current
    val preview = remember(context) {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(WebSettings.getDefaultUserAgent(context))
            .setDefaultRequestProperties(mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
            ))
        val mediaSource = DefaultMediaSourceFactory(context).setDataSourceFactory(http)
        val player = ExoPlayer.Builder(context).setMediaSourceFactory(mediaSource).build().apply {
            setAudioAttributes(AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA).build(), true)
            setHandleAudioBecomingNoisy(true)
        }
        MusicPreviewPlayer(player)
    }
    DisposableEffect(preview) { onDispose { preview.release() } }
    LaunchedEffect(preview) {
        while (true) {
            preview.updatePosition()
            delay(300)
        }
    }
    return preview
}

package com.domenota.medialoader.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.FilteringMediaSource
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.provider.PreviewPlayback
import com.domenota.medialoader.core.provider.PreviewTrack
import com.domenota.medialoader.ui.model.MediaKind
import com.domenota.medialoader.ui.model.PreviewMediaUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

@androidx.annotation.OptIn(UnstableApi::class)
private fun previewSource(context: Context, track: PreviewTrack): MediaSource {
    val http = DefaultHttpDataSource.Factory()
        .setUserAgent(track.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
        .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
        .setDefaultRequestProperties(track.headers)
    val item = MediaItem.Builder().setUri(track.url).setMimeType(track.mimeType).build()
    return DefaultMediaSourceFactory(context)
        .setDataSourceFactory(DefaultDataSource.Factory(context, http)).createMediaSource(item)
}

/** One short-lived player. Network buffers stay in memory; downloads/history are never touched. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun StreamingMediaPreview(item: PreviewMediaUi, resolve: suspend (String) -> PreviewPlayback,
    modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resolver by rememberUpdatedState(resolve)
    val player = remember(item.id) {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(if (item.kind == MediaKind.AUDIO) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), true)
            setHandleAudioBecomingNoisy(true)
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, item.kind == MediaKind.AUDIO).build()
        }
    }
    var attempt by remember(item.id) { mutableIntStateOf(0) }
    var resolving by remember(item.id) { mutableStateOf(true) }
    var buffering by remember(item.id) { mutableStateOf(false) }
    var playing by remember(item.id) { mutableStateOf(false) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    var position by remember(item.id) { mutableLongStateOf(0L) }
    var duration by remember(item.id) { mutableLongStateOf(0L) }
    var pendingSeek by remember(item.id) { mutableStateOf<Long?>(null) }
    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
                buffering = player.playbackState == Player.STATE_BUFFERING
                duration = player.duration.takeIf { it > 0L } ?: 0L
            }
            override fun onPlayerError(cause: PlaybackException) {
                error = "Источник не разрешил предпросмотр. Можно повторить попытку или скачать файл."
                buffering = false; playing = false
                AppLog.w("RemotePreview", "Playback failed · code=${cause.errorCode}")
            }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        player.addListener(listener); lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer); player.removeListener(listener); player.release()
        }
    }
    LaunchedEffect(player, attempt) {
        resolving = true; error = null
        player.stop()
        try {
            val source = resolver(item.id)
            currentCoroutineContext().ensureActive()
            val video = previewSource(context, source.primary)
            val media = source.audio?.let { MergingMediaSource(true, true,
                FilteringMediaSource(video, C.TRACK_TYPE_VIDEO),
                FilteringMediaSource(previewSource(context, it), C.TRACK_TYPE_AUDIO)) } ?: video
            source.maxVideoHeight?.let { height ->
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setMaxVideoSize(Int.MAX_VALUE, height).build()
            }
            player.setMediaSource(media); player.prepare()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) player.play()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Не удалось открыть предпросмотр. Повтори попытку или обнови анализ ссылки." }
        finally { resolving = false }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.takeIf { it > 0L } ?: 0L
            delay(200)
        }
    }
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            if (item.kind == MediaKind.VIDEO) TextEditorVideoSurface(player, Modifier.fillMaxSize())
            else Icon(Icons.Rounded.AudioFile, contentDescription = "Аудио", tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(88.dp))
            if (resolving || buffering) CircularProgressIndicator()
            error?.let { message ->
                Surface(Modifier.fillMaxWidth().padding(20.dp), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(message)
                        OutlinedButton(onClick = { attempt++ }) { Text("Повторить") }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (duration > 0L) AppSlider((pendingSeek ?: position).coerceIn(0L, duration).toFloat(),
                onValueChange = { pendingSeek = it.toLong() }, valueRange = 0f..duration.toFloat(),
                onValueChangeFinished = { pendingSeek?.let(player::seekTo); pendingSeek = null },
                enabled = error == null && !resolving)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(streamTime(pendingSeek ?: position), Modifier.weight(1f))
                OutlinedIconButton(onClick = {
                    if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) player.pause()
                    else {
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                        player.play()
                    }
                }, enabled = !resolving && error == null) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playing) "Пауза" else "Воспроизвести")
                }
                Text(if (duration > 0) streamTime(duration) else "—:—", Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}

private fun streamTime(ms: Long): String = "%d:%02d".format(ms / 60_000L, ms / 1000L % 60)

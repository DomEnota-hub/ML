package com.domenota.medialoader.ui.components
import com.domenota.medialoader.ui.theme.musicPlaybackAccent

import android.content.ComponentName
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.data.playback.AudioPlaybackMetadata
import com.domenota.medialoader.ui.model.MediaKind
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.domenota.medialoader.data.playback.AudioPlaybackService

@Composable
fun AudioMiniPlayer(
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenQueue: ((String, List<String>) -> Unit)? = null,
    compact: Boolean = false,
    interactiveSeek: Boolean = false,
) {
    val context = LocalContext.current
    val token = remember {
        SessionToken(context, ComponentName(context, AudioPlaybackService::class.java))
    }
    val controllerFuture = remember(token) {
        MediaController.Builder(context, token).buildAsync()
    }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var mediaId by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var mediaCount by remember { mutableStateOf(0) }
    var privateMedia by remember { mutableStateOf(false) }
    var savedUri by remember { mutableStateOf<String?>(null) }
    var artworkUri by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableLongStateOf(0) }
    var duration by remember { mutableLongStateOf(0) }
    var seekable by remember { mutableStateOf(false) }
    var trackGeneration by remember { mutableLongStateOf(0) }

    DisposableEffect(controllerFuture) {
        controllerFuture.addListener(
            {
                runCatching { controllerFuture.get() }.getOrNull()?.let { connected ->
                    controller = connected
                    mediaId = connected.currentMediaItem?.mediaId
                    title = connected.mediaMetadata.title?.toString().orEmpty()
                    artist = connected.mediaMetadata.artist?.toString()
                    isPlaying = connected.isPlaying
                    mediaCount = connected.mediaItemCount
                    privateMedia = connected.mediaMetadata.extras?.getBoolean("private_media") == true
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            MediaController.releaseFuture(controllerFuture)
            controller = null
        }
    }

    DisposableEffect(controller) {
        val active = controller
        if (active == null) {
            onDispose { }
        } else {
            fun sync(mediaItem: MediaItem? = active.currentMediaItem) {
                mediaId = mediaItem?.mediaId
                title = active.mediaMetadata.title?.toString().orEmpty()
                artist = active.mediaMetadata.artist?.toString()
                isPlaying = active.playWhenReady
                mediaCount = active.mediaItemCount
                privateMedia = active.mediaMetadata.extras?.getBoolean("private_media") == true
                savedUri = active.mediaMetadata.extras?.getString(AudioPlaybackMetadata.PUBLIC_URI)
                    ?: mediaItem?.localConfiguration?.uri?.toString()
                artworkUri = active.mediaMetadata.artworkUri?.toString()
                position=active.currentPosition.coerceAtLeast(0L)
                duration=active.duration.coerceAtLeast(0L)
                seekable=active.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)&&active.isCurrentMediaItemSeekable
            }

            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(value: Boolean) {
                    isPlaying = active.playWhenReady
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    isPlaying = playWhenReady
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    trackGeneration++
                    sync(mediaItem)
                }
                override fun onAvailableCommandsChanged(availableCommands: Player.Commands) { sync() }
                override fun onPlaybackStateChanged(playbackState: Int) { sync() }

                override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                    privateMedia = mediaMetadata.extras?.getBoolean("private_media") == true
                    title = mediaMetadata.title?.toString().orEmpty()
                    artist = mediaMetadata.artist?.toString()
                    savedUri = mediaMetadata.extras?.getString(AudioPlaybackMetadata.PUBLIC_URI)
                        ?: active.currentMediaItem?.localConfiguration?.uri?.toString()
                    artworkUri = mediaMetadata.artworkUri?.toString()
                }

                override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                    sync()
                }
            }
            active.addListener(listener)
            sync()
            onDispose { active.removeListener(listener) }
        }
    }

    LaunchedEffect(controller) {
        val active = controller ?: return@LaunchedEffect
        while (true) {
            position = active.currentPosition.coerceAtLeast(0L)
            duration = active.duration.coerceAtLeast(0L)
            delay(400)
        }
    }
    if (privateMedia) return
    val currentId = mediaId ?: return
    fun openCurrent() {
        val active = controller
        if (active != null && onOpenQueue != null) onOpenQueue(currentId,
            (0 until active.mediaItemCount).map { active.getMediaItemAt(it).mediaId })
        else onOpen(currentId)
    }
    LibraryTheme {
        Surface(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compact) 3.dp else 8.dp),
            shape = RoundedCornerShape(if (compact) 18.dp else 14.dp),
            shadowElevation = if (compact) 5.dp else 0.dp,
            color = MaterialTheme.colorScheme.surfaceContainer) {
            Column {
                Row(Modifier.fillMaxWidth().clickable(onClick = ::openCurrent).padding(horizontal = 8.dp, vertical = if (compact) 4.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.size(if (compact) 32.dp else 34.dp).clip(RoundedCornerShape(6.dp))) {
                        DownloadThumbnail(savedUri, artworkUri, MediaKind.AUDIO, Modifier.fillMaxSize())
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        Text(title.ifBlank { "Аудио" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                        if (!artist.isNullOrBlank()) Text(artist.orEmpty(), Modifier.padding(top = 3.dp), fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!compact) IconButton({ controller?.seekToPrevious() }, Modifier.size(40.dp), enabled = mediaCount > 0) {
                        Icon(Icons.Rounded.SkipPrevious, "Предыдущий трек", Modifier.size(22.dp))
                    }
                    IconButton({ controller?.let { com.domenota.medialoader.data.playback.AudioQueuePlayback.toggle(it) } }, Modifier.size(40.dp)) {
                        Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (isPlaying) "Пауза" else "Воспроизвести", Modifier.size(24.dp))
                    }
                    if (!compact) IconButton({ controller?.seekToNextMediaItem() }, Modifier.size(40.dp), enabled = mediaCount > 1) {
                        Icon(Icons.Rounded.SkipNext, "Следующий трек", Modifier.size(22.dp))
                    }
                    IconButton({ controller?.let { it.pause(); it.stop(); it.clearMediaItems() } }, Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.Close, "Закрыть плеер", Modifier.size(22.dp))
                    }
                }
                val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                if(interactiveSeek){
                    androidx.compose.runtime.key(currentId,trackGeneration){
                        var pending by remember { mutableStateOf<Float?>(null) }
                        val generation=trackGeneration
                        AppSlider(value=pending?:progress,onValueChange={pending=it},
                            modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp).testTag("collection-mini-seek")
                                .semantics{contentDescription="Перемотка трека"},
                            enabled=seekable&&duration>0L,
                            onValueChangeFinished={
                                val fraction=pending;pending=null
                                val active=controller
                                if(fraction!=null&&active!=null&&generation==trackGeneration&&active.currentMediaItem?.mediaId==currentId&&
                                    active.isCurrentMediaItemSeekable&&active.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)&&
                                    active.duration>0&&active.currentMediaItem?.mediaMetadata?.extras?.getBoolean("private_media")!=true){
                                    val target=(active.duration*fraction.toDouble()).toLong().coerceIn(0L,active.duration)
                                    active.seekTo(target);position=target
                                }
                            },accentColor=musicPlaybackAccent)
                        Row(Modifier.fillMaxWidth().padding(start=12.dp,end=12.dp,bottom=6.dp),horizontalArrangement=Arrangement.SpaceBetween){
                            Text(formatMiniTime((pending?.let{(duration*it.toDouble()).toLong()}?:position)),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if(duration>0)formatMiniTime(duration) else "—",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }else Box(Modifier.fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))) {
                    Box(Modifier.fillMaxWidth(progress).height(3.dp).background(musicPlaybackAccent))
                }
            }
        }
    }

}

private fun formatMiniTime(ms:Long):String {
    val sec=ms.coerceAtLeast(0L)/1000
    return if(sec>=3600) "%d:%02d:%02d".format(sec/3600,sec/60%60,sec%60) else "%d:%02d".format(sec/60,sec%60)
}

package com.domenota.medialoader.ui.components
import com.domenota.medialoader.ui.theme.musicPlaybackAccent

import com.domenota.medialoader.ui.model.displayFileName
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
import com.domenota.medialoader.data.playback.PlaybackDurationState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.disabled
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
    var titleIsFilename by remember { mutableStateOf(false) }
    var artist by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var mediaCount by remember { mutableStateOf(0) }
    var privateMedia by remember { mutableStateOf(false) }
    var savedUri by remember { mutableStateOf<String?>(null) }
    var artworkUri by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableLongStateOf(0) }
    var durationState by remember { mutableStateOf(PlaybackDurationState()) }
    val duration=durationState.durationMs
    var seekable by remember { mutableStateOf(false) }
    var trackGeneration by remember { mutableLongStateOf(0) }

    DisposableEffect(controllerFuture) {
        controllerFuture.addListener(
            {
                runCatching { controllerFuture.get() }.getOrNull()?.let { connected ->
                    controller = connected
                    mediaId = connected.currentMediaItem?.mediaId
                    title = connected.mediaMetadata.title?.toString().orEmpty()
                    titleIsFilename = connected.mediaMetadata.extras?.getBoolean(AudioPlaybackMetadata.FILENAME_TITLE) == true
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
                titleIsFilename = active.mediaMetadata.extras?.getBoolean(AudioPlaybackMetadata.FILENAME_TITLE) == true
                artist = active.mediaMetadata.artist?.toString()
                isPlaying = active.playWhenReady
                mediaCount = active.mediaItemCount
                privateMedia = active.mediaMetadata.extras?.getBoolean("private_media") == true
                savedUri = active.mediaMetadata.extras?.getString(AudioPlaybackMetadata.PUBLIC_URI)
                    ?: mediaItem?.localConfiguration?.uri?.toString()
                artworkUri = active.mediaMetadata.artworkUri?.toString()
                position=active.currentPosition.coerceAtLeast(0L)
                durationState=durationState.update(mediaItem?.let(AudioPlaybackMetadata::durationIdentity),active.duration,
                    mediaItem?.mediaMetadata?.extras?.getLong(AudioPlaybackMetadata.VERIFIED_DURATION))
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
                    titleIsFilename = mediaMetadata.extras?.getBoolean(AudioPlaybackMetadata.FILENAME_TITLE) == true
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
            val item=active.currentMediaItem
            durationState=durationState.update(item?.let(AudioPlaybackMetadata::durationIdentity),active.duration,
                item?.mediaMetadata?.extras?.getLong(AudioPlaybackMetadata.VERIFIED_DURATION))
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
                        Text((if(titleIsFilename) displayFileName(title, MediaKind.AUDIO) else title).ifBlank { "Аудио" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
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
                    val generation=trackGeneration
                    val identity=durationState.identity
                    androidx.compose.runtime.key(currentId,generation,identity){
                        CompactAudioSeek(progress,seekable&&duration>0L&&identity!=null){fraction->
                            val active=controller
                            val item=active?.currentMediaItem
                            val currentDuration=active?.duration?.takeIf{it>0L}
                                ?: durationState.durationMs.takeIf{durationState.identity==identity&&it>0L}
                            if(active!=null&&item!=null&&currentDuration!=null&&generation==trackGeneration&&identity==AudioPlaybackMetadata.durationIdentity(item)&&
                                item.mediaId==currentId&&active.isCurrentMediaItemSeekable&&
                                active.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)&&
                                item.mediaMetadata.extras?.getBoolean("private_media")!=true&&
                                active.mediaMetadata.extras?.getBoolean("private_media")!=true){
                                val target=(currentDuration*fraction.toDouble()).toLong().coerceIn(0L,currentDuration)
                                active.seekTo(target);position=target;true
                            }else false
                        }
                    }
                }else Box(Modifier.fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))) {
                    Box(Modifier.fillMaxWidth(progress).height(3.dp).background(musicPlaybackAccent))
                }
            }
        }
    }

}


/** A visually 3dp progress line; the independent 20dp gesture strip does not overlap controls.
 * Tap/drag commits once on release, and TalkBack has the normal SetProgress action. */
@Composable
internal fun CompactAudioSeek(progress:Float,enabled:Boolean,onSeek:(Float)->Boolean){
    var pending by remember{mutableStateOf<Float?>(null)}
    val currentSeek by rememberUpdatedState(onSeek)
    val shown=(pending?:progress).coerceIn(0f,1f)
    LaunchedEffect(enabled){if(!enabled)pending=null}
    val gesture=if(enabled)Modifier.pointerInput(Unit){
        detectTapGestures { point->currentSeek((point.x/size.width.coerceAtLeast(1)).coerceIn(0f,1f)) }
    }.pointerInput(Unit){
        detectHorizontalDragGestures(
            onDragStart={point->pending=(point.x/size.width.coerceAtLeast(1)).coerceIn(0f,1f)},
            onDragCancel={pending=null},
            onDragEnd={val value=pending;pending=null;if(value!=null)currentSeek(value)}
        ){change,_->pending=(change.position.x/size.width.coerceAtLeast(1)).coerceIn(0f,1f);change.consume()}
    }else Modifier
    Box(Modifier.fillMaxWidth().height(20.dp).testTag("collection-mini-seek")
        .semantics {
            contentDescription="Перемотка трека"
            progressBarRangeInfo=ProgressBarRangeInfo(shown,0f..1f)
            if(enabled)setProgress{value->currentSeek(value.coerceIn(0f,1f))} else disabled()
        }.then(gesture),contentAlignment=Alignment.BottomStart){
        Box(Modifier.fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha=.08f))){
            Box(Modifier.fillMaxWidth(shown).height(3.dp).background(musicPlaybackAccent))
        }
    }
}

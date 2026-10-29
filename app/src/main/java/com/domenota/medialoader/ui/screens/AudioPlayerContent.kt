package com.domenota.medialoader.ui.screens

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import com.domenota.medialoader.data.playback.AudioPlaybackMetadata
import com.domenota.medialoader.ui.screens.music.MusicPlayerControls
import com.domenota.medialoader.ui.screens.music.MusicLibraryTrackRow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.playback.AudioPlaybackService
import com.domenota.medialoader.ui.components.AudioPlaybackModes
import com.domenota.medialoader.ui.components.AudioWaveform
import com.domenota.medialoader.ui.components.loadAudioWaveform
import com.domenota.medialoader.ui.components.peekAudioWaveform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun BackgroundAudioPlayer(
    item: DownloadEntity,
    uri: Uri,
    queue: List<DownloadEntity>,
    onCurrentItemChanged: (DownloadEntity) -> Unit = {},
    modifier: Modifier = Modifier,
    libraryLayout: Boolean = false, metadataLookup: List<DownloadEntity> = emptyList(), blockedIdentities: Set<String> = emptySet(),
    onBack: () -> Unit = {}, onEqualizer: (() -> Unit)? = null,
    favoriteIds: Set<String> = emptySet(), onFavorite: ((DownloadEntity) -> Unit)? = null, onEdit: ((DownloadEntity) -> Unit)? = null,
) {
    val context = LocalContext.current
    val playableQueue = remember(item.id, queue) {
        val candidates = queue
            .filter { !it.hidden && !it.savedUri.isNullOrBlank() }
            .distinctBy { it.id }
        if (candidates.any { it.id == item.id }) candidates else listOf(item) + candidates
    }
    val token = remember {
        SessionToken(context, ComponentName(context, AudioPlaybackService::class.java))
    }
    val controllerFuture = remember(token) {
        MediaController.Builder(context, token).buildAsync()
    }

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var currentItem by remember { mutableStateOf(item) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var shuffleEnabled by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var speedMenuOpen by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var artwork by remember { mutableStateOf<Bitmap?>(null) }
    var waveform by remember { mutableStateOf<FloatArray?>(null) }
    var sessionItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var privateSession by remember { mutableStateOf(false) }
    var currentIdentityKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var queueOpen by remember { mutableStateOf(false) }
    val latestCallback by rememberUpdatedState(onCurrentItemChanged)
    val lookup = if (metadataLookup.isEmpty()) playableQueue else metadataLookup
    fun syncSession(active: MediaController) {
        val media = active.currentMediaItem
        if (media != null) {
            currentIdentityKeys = media.mediaMetadata.extras?.getStringArrayList(com.domenota.medialoader.data.playback.PublicMediaIdentity.EXTRA_KEYS).orEmpty().toSet()
            privateSession = media.mediaMetadata.extras?.getBoolean("private_media") == true
            val row = if (libraryLayout) AudioPlaybackMetadata.publicRow(media, lookup, blockedIdentities)
                else lookup.firstOrNull { it.id == media.mediaId } ?: currentItem
            if (row != null) { currentItem = row; latestCallback(row) }
        }
        sessionItems = (0 until active.mediaItemCount).map { active.getMediaItemAt(it) }
        currentIndex = active.currentMediaItemIndex.coerceAtLeast(0)
        durationMs = active.duration.coerceAtLeast(0L)
        repeatMode = active.repeatMode
        shuffleEnabled = active.shuffleModeEnabled
        playbackSpeed = active.playbackParameters.speed
        isPlaying = if (libraryLayout) active.playWhenReady else active.isPlaying
    }

    DisposableEffect(controllerFuture) {
        controllerFuture.addListener(
            {
                runCatching { controllerFuture.get() }.getOrNull()?.let { connected ->
                    controller = connected
                    isPlaying = connected.isPlaying
                    repeatMode = connected.repeatMode
                    shuffleEnabled = connected.shuffleModeEnabled
                    playbackSpeed = connected.playbackParameters.speed
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            MediaController.releaseFuture(controllerFuture)
            controller = null
        }
    }

    LaunchedEffect(controller, item.id, playableQueue, libraryLayout) {
        val active = controller ?: return@LaunchedEffect
        if (libraryLayout && active.mediaItemCount > 0) { syncSession(active); return@LaunchedEffect }
        val desiredItems = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            playableQueue.map { entry ->
                val identity = entry.savedUri?.let {
                    com.domenota.medialoader.data.playback.PublicMediaIdentity.capture(context, it)
                }.orEmpty()
                toAudioMediaItem(entry, identity)
            }
        }
        val desiredIds = playableQueue.map { it.id }
        val currentIds = (0 until active.mediaItemCount).map { index ->
            active.getMediaItemAt(index).mediaId
        }
        val currentId = active.currentMediaItem?.mediaId
        val requestedIndex = desiredIds.indexOf(item.id).coerceAtLeast(0)

        val currentPrivate = active.currentMediaItem?.mediaMetadata?.extras?.getBoolean("private_media") == true
        if (libraryLayout) {
            active.setMediaItems(desiredItems, requestedIndex, 0L)
            active.prepare()
            active.playWhenReady = false
            syncSession(active)
            return@LaunchedEffect
        }
        if (currentIds != desiredIds || currentPrivate != item.isPrivate) {
            val wasPlaying = active.playWhenReady
            val oldPosition = active.currentPosition.coerceAtLeast(0L)
            val targetId = if (currentId == item.id) currentId else item.id
            val targetIndex = desiredIds.indexOf(targetId).coerceAtLeast(requestedIndex)
            val targetPosition = if (targetId == currentId) oldPosition else 0L
            active.setMediaItems(desiredItems, targetIndex, targetPosition)
            active.prepare()
            active.playWhenReady = if (currentId != item.id) true else wasPlaying
        } else if (currentId != item.id) {
            active.seekTo(requestedIndex, 0L)
            active.prepare()
            active.play()
        }

        val activeId = active.currentMediaItem?.mediaId ?: item.id
        currentItem = playableQueue.firstOrNull { it.id == activeId } ?: item
        onCurrentItemChanged(currentItem)
        currentIndex = active.currentMediaItemIndex.coerceAtLeast(0)
    }

    DisposableEffect(controller, playableQueue, lookup, blockedIdentities) {
        val active = controller
        if (active == null) {
            onDispose { }
        } else {
            fun syncCurrent() {
                syncSession(active)
            }

            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(value: Boolean) {
                    isPlaying = if (libraryLayout) active.playWhenReady else value
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (libraryLayout) isPlaying = playWhenReady }
                override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { syncCurrent() }
                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) { syncCurrent() }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    syncCurrent()
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    syncCurrent()
                    positionMs = max(0L, active.currentPosition)
                }

                override fun onRepeatModeChanged(mode: Int) {
                    repeatMode = mode
                }

                override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                    shuffleEnabled = enabled
                }

                override fun onPlaybackParametersChanged(parameters: androidx.media3.common.PlaybackParameters) {
                    playbackSpeed = parameters.speed
                }
            }
            active.addListener(listener)
            syncCurrent()
            onDispose { active.removeListener(listener) }
        }
    }

    LaunchedEffect(controller) {
        val active = controller ?: return@LaunchedEffect
        while (true) {
            positionMs = max(0L, active.currentPosition)
            durationMs = active.duration.takeIf { it > 0 } ?: durationMs
            currentIndex = active.currentMediaItemIndex.coerceAtLeast(0)
            delay(400)
        }
    }

    val currentUri = currentItem.savedUri?.let(Uri::parse) ?: uri
    if (!libraryLayout) LaunchedEffect(currentUri) {
        artwork = withContext(Dispatchers.IO) { embeddedArtwork(context, currentUri) }
    }
    if (!libraryLayout) LaunchedEffect(currentUri) {
        waveform = peekAudioWaveform(context, currentUri)
        waveform = withContext(Dispatchers.IO) { loadAudioWaveform(context, currentUri) }
    }

    if (libraryLayout) {
        val blocked = privateSession || currentItem.id in blockedIdentities || currentIdentityKeys.any(blockedIdentities::contains)
        if (blocked) Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Воспроизведение в «Личном»")
            TextButton(onBack) { Text("Назад") }
        } else androidx.compose.foundation.layout.Box(modifier) {
            MusicPlayerControls(currentItem, controller, positionMs, durationMs, isPlaying, shuffleEnabled, repeatMode, playbackSpeed,
                onBack, onEqualizer, { queueOpen = true }, onFavorite?.let { action -> { action(currentItem) } },
                favorite = currentItem.id in favoriteIds, onEdit = onEdit?.let { action -> { action(currentItem) } })
        }
        if (queueOpen && !blocked) ModalBottomSheet(onDismissRequest = { queueOpen = false }) {
        com.domenota.medialoader.ui.theme.TransparentWindowNavigation()
            Text("Очередь воспроизведения", Modifier.padding(17.dp), style = MaterialTheme.typography.titleLarge)
            com.domenota.medialoader.ui.screens.music.EditableAudioQueue(
                sessionItems, controller, currentIndex, isPlaying, lookup, blockedIdentities,
                onSelect = { index ->
                    controller?.let { active ->
                        if (index == active.currentMediaItemIndex) {
                            if (active.playWhenReady) active.pause() else active.play()
                        } else { active.seekTo(index, 0L); active.play() }
                    }
                    queueOpen = false
                })
        }
        return
    }

    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(260.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (artwork != null) {
                Image(
                    bitmap = artwork!!.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(260.dp),
                )
            } else {
                Text(
                    "♪",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            audioTitle(currentItem),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val artist = audioArtist(currentItem)
        if (!artist.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                artist,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (playableQueue.size > 1) {
            Spacer(Modifier.height(6.dp))
            Text(
                "${(currentIndex + 1).coerceAtMost(playableQueue.size)} из ${playableQueue.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(20.dp))
        if (waveform != null) {
            AudioWaveform(
                peaks = waveform!!,
                progress = (positionMs.toFloat() / durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f),
                onSeekFraction = { fraction ->
                    if (durationMs > 0) controller?.seekTo((durationMs * fraction).toLong())
                },
            )
        } else {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatAudioTime(positionMs), style = MaterialTheme.typography.labelMedium)
            Text(formatAudioTime(durationMs), style = MaterialTheme.typography.labelMedium)
        }

        Spacer(Modifier.height(8.dp))
        if (controller == null) {
            CircularProgressIndicator()
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                IconButton(
                    onClick = { controller?.seekToPreviousMediaItem() },
                    enabled = playableQueue.size > 1,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        Icons.Rounded.SkipPrevious,
                        contentDescription = "Предыдущий трек",
                        modifier = Modifier.size(34.dp),
                    )
                }

                IconButton(
                    onClick = {
                        controller?.let {
                            com.domenota.medialoader.data.playback.AudioQueuePlayback.toggle(it)
                        }
                    },
                    modifier = Modifier.size(72.dp),
                ) {
                    Icon(
                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                        modifier = Modifier.size(52.dp),
                    )
                }

                IconButton(
                    onClick = { controller?.seekToNextMediaItem() },
                    enabled = playableQueue.size > 1,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        Icons.Rounded.SkipNext,
                        contentDescription = "Следующий трек",
                        modifier = Modifier.size(34.dp),
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            controller?.let { AudioPlaybackModes(it, repeatMode, shuffleEnabled) }
            Spacer(Modifier.height(10.dp))
            Box {
                FilledTonalButton(
                    onClick = { speedMenuOpen = true },
                    enabled = controller != null,
                    modifier = Modifier.height(48.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) {
                    Text(
                        "Скорость · ${if (playbackSpeed == 1f) "1" else playbackSpeed.toString()}×",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                DropdownMenu(expanded = speedMenuOpen, onDismissRequest = { speedMenuOpen = false }) {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { choice ->
                        DropdownMenuItem(
                            text = { Text("${if (choice == 1f) "1" else choice.toString()}×") },
                            onClick = {
                                controller?.setPlaybackSpeed(choice)
                                playbackSpeed = choice
                                speedMenuOpen = false
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Воспроизведение продолжится в фоне",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun toAudioMediaItem(item: DownloadEntity, identityKeys: Set<String> = emptySet()): MediaItem =
    com.domenota.medialoader.data.playback.AudioPlaybackMetadata.item(item, identityKeys)

internal fun audioTitle(item: DownloadEntity): String =
    item.recognizedTitle ?: item.sourceAudioTitle ?: item.originalName

internal fun audioArtist(item: DownloadEntity): String? =
    item.recognizedArtist ?: item.sourceAudioArtist

private fun embeddedArtwork(context: android.content.Context, uri: Uri): Bitmap? =
    runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } finally {
            retriever.release()
        }
    }.getOrNull()

private fun formatAudioTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

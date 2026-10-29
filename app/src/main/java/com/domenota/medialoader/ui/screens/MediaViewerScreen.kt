package com.domenota.medialoader.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.NumberPicker
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Settings as SettingsIcon
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import com.domenota.medialoader.ui.components.NeutralOutlinedButton as OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.playback.FloatingVideoService
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.search.FullSongSource
import com.domenota.medialoader.core.recognition.MusicRecognitionResult
import com.domenota.medialoader.ui.components.FullSongSearchDialog
import com.domenota.medialoader.data.editor.VideoScrubPolicy
import com.domenota.medialoader.data.editor.VideoGestureMode
import com.domenota.medialoader.data.editor.VideoGesturePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(
    item: DownloadEntity,
    onBack: () -> Unit,
    onOpenExternal: (DownloadEntity) -> Unit,
    onShare: (DownloadEntity) -> Unit,
    audioQueue: List<DownloadEntity> = emptyList(),
    photoQueue: List<DownloadEntity> = emptyList(),
    mediaQueue: List<DownloadEntity> = emptyList(),
    onEdit: ((DownloadEntity) -> Unit)? = null,
    trashReadOnly: Boolean = false,
    onRestoreFromTrash: ((DownloadEntity) -> Unit)? = null,
    onDeleteFromTrash: ((DownloadEntity) -> Unit)? = null,
    modernAudioLayout: Boolean = false, audioMetadataRows: List<DownloadEntity> = emptyList(),
    audioBlockedIdentities: Set<String> = emptySet(), audioFavoriteIds: Set<String> = emptySet(),
    onAudioFavorite: ((DownloadEntity) -> Unit)? = null, onEqualizer: (() -> Unit)? = null,
    onAudioCurrentItemChanged: (DownloadEntity) -> Unit = {},
) {
    var selectedId by rememberSaveable(item.id) { mutableStateOf(item.id) }
    val selectedItem = mediaQueue.firstOrNull { it.id == selectedId } ?: item
    val selectedIndex = mediaQueue.indexOfFirst { it.id == selectedItem.id }
    var videoFullscreen by rememberSaveable(selectedItem.id) { mutableStateOf(false) }
    var videoControlsLocked by rememberSaveable(selectedItem.id) { mutableStateOf(false) }
    var landscapeVideo by rememberSaveable(selectedItem.id) { mutableStateOf(false) }
    var displayedItem by remember(selectedItem.id) { mutableStateOf(selectedItem) }
    var actionMenuOpen by remember(displayedItem.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val view = LocalView.current
    DisposableEffect(selectedItem.id, trashReadOnly) {
        AppLog.verbose("Viewer", "Opened · id=${selectedItem.id} · trash=$trashReadOnly · type=${selectedItem.mediaType}")
        onDispose { AppLog.verbose("Viewer", "Closed · id=${selectedItem.id} · trash=$trashReadOnly") }
    }
    var confirmTrashDelete by remember { mutableStateOf(false) }
    var songSearchOpen by remember(selectedItem.id) { mutableStateOf(false) }
    var songSearchBusy by remember(selectedItem.id) { mutableStateOf(false) }
    var songSearchResult by remember(selectedItem.id) { mutableStateOf<MusicRecognitionResult?>(null) }
    var songSearchMessage by remember(selectedItem.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(songSearchOpen) {
        if (songSearchOpen && selectedItem.mediaType == MediaType.VIDEO) {
            songSearchBusy = true
            songSearchResult = runCatching {
                MediaRepository.get(context).recognizeSavedVideo(selectedItem.savedUri.orEmpty())
            }.getOrNull()
            songSearchMessage = if (songSearchResult == null) {
                "Композицию определить не удалось. Введите исполнителя и название вручную."
            } else "Трек распознан по звуку видео"
            songSearchBusy = false
        }
    }

    if (songSearchOpen) {
        if (songSearchBusy) AlertDialog(
            onDismissRequest = { songSearchOpen = false },
            title = { Text("Ищем полную песню") },
            text = { CircularProgressIndicator() },
            confirmButton = { TextButton(onClick = { songSearchOpen = false }) { Text("Отмена") } },
        ) else FullSongSearchDialog(
            source = FullSongSource.fromStored(context.getSharedPreferences("ui", 0)
                .getString(FullSongSource.PREF_KEY, null)),
            initialArtist = songSearchResult?.artist.orEmpty(),
            initialTitle = songSearchResult?.title.orEmpty(),
            recognizedArtworkUrl = songSearchResult?.artworkUrl,
            recognitionMessage = songSearchMessage,
            onDismiss = { songSearchOpen = false },
        )
    }

    if (confirmTrashDelete) AlertDialog(
        onDismissRequest = { confirmTrashDelete = false },
        title = { Text("Удалить навсегда?") },
        text = { Text("Этот файл нельзя будет восстановить.") },
        confirmButton = {
            TextButton(onClick = {
                confirmTrashDelete = false
                onDeleteFromTrash?.invoke(displayedItem)
            }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmTrashDelete = false }) { Text("Отмена") } },
    )

    DisposableEffect(videoFullscreen, landscapeVideo, activity, view) {
        if (selectedItem.mediaType == MediaType.VIDEO && activity != null) {
            applyFullscreen(
                activity = activity,
                decorView = view,
                fullscreen = videoFullscreen,
                landscapeVideo = landscapeVideo,
            )
        }
        onDispose {
            if (activity != null && videoFullscreen) {
                applyFullscreen(
                    activity = activity,
                    decorView = view,
                    fullscreen = false,
                    landscapeVideo = landscapeVideo,
                )
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (mediaQueue.size > 1 && selectedIndex >= 0 && !videoControlsLocked) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selectedId = mediaQueue[selectedIndex - 1].id },
                        enabled = selectedIndex > 0) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Предыдущий файл")
                    }
                    Text("${selectedIndex + 1} из ${mediaQueue.size}")
                    IconButton(onClick = { selectedId = mediaQueue[selectedIndex + 1].id },
                        enabled = selectedIndex < mediaQueue.lastIndex) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Следующий файл",
                            modifier = Modifier.graphicsLayer { rotationZ = 180f })
                    }
                }
            }

            if (trashReadOnly && !videoFullscreen) Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { onRestoreFromTrash?.invoke(displayedItem) },
                        enabled = onRestoreFromTrash != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("Восстановить") }
                    OutlinedButton(
                        onClick = { confirmTrashDelete = true },
                        enabled = onDeleteFromTrash != null,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Удалить") }
                }
            }
        },
        topBar = {
            if (!videoFullscreen && !(modernAudioLayout && selectedItem.mediaType == MediaType.AUDIO)) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад",
                                modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
                        }
                        Text(
                            displayedItem.originalName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        if (onEdit != null) {
                            IconButton(onClick = { onEdit(displayedItem) }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.Edit, contentDescription = "Редактировать",
                                    modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        if (!trashReadOnly && !displayedItem.isPrivate) Box {
                            IconButton(onClick = { actionMenuOpen = true }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "Действия с файлом",
                                    modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
                            }
                            DropdownMenu(expanded = actionMenuOpen,
                                onDismissRequest = { actionMenuOpen = false }) {
                                if (displayedItem.mediaType == MediaType.VIDEO) DropdownMenuItem(
                                    text = { Text("Найти полную песню") },
                                    leadingIcon = { Icon(Icons.Outlined.MusicNote, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp)) },
                                    onClick = {
                                        actionMenuOpen = false
                                        songSearchBusy = true
                                        songSearchOpen = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Поделиться") },
                                    leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp)) },
                                    onClick = { actionMenuOpen = false; onShare(displayedItem) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Открыть другим приложением") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp)) },
                                    onClick = { actionMenuOpen = false; onOpenExternal(displayedItem) },
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        val uri = selectedItem.savedUri?.let(Uri::parse)
        if (uri == null) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("Файл недоступен")
            }
            return@Scaffold
        }

        when (selectedItem.mediaType) {
            MediaType.PHOTO -> PhotoPager(
                startItem = selectedItem,
                queue = if (mediaQueue.isEmpty()) photoQueue else emptyList(),
                onCurrentItemChanged = { displayedItem = it },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            MediaType.VIDEO -> key(selectedItem.id) { LocalVideoPlayer(
                itemId = selectedItem.id,
                uri = uri,
                fullscreen = videoFullscreen,
                controlsLocked = videoControlsLocked,
                onControlsLockedChange = { videoControlsLocked = it },
                onFullscreenChange = { videoFullscreen = it },
                onVideoOrientation = { landscapeVideo = it },
                onOpenExternal = { onOpenExternal(selectedItem) },
                allowExternal = !selectedItem.isPrivate,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) }
            MediaType.AUDIO -> BackgroundAudioPlayer(
                item = selectedItem,
                uri = uri,
                queue = audioQueue,
                onCurrentItemChanged = { displayedItem = it; onAudioCurrentItemChanged(it) },
                modifier = Modifier.fillMaxSize().padding(padding),
                libraryLayout = modernAudioLayout, metadataLookup = audioMetadataRows, blockedIdentities = audioBlockedIdentities,
                onBack = onBack, onEqualizer = onEqualizer, favoriteIds = audioFavoriteIds, onFavorite = onAudioFavorite, onEdit = onEdit,
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
internal fun LocalVideoPlayer(
    itemId: String,
    uri: Uri,
    fullscreen: Boolean,
    controlsLocked: Boolean,
    onControlsLockedChange: (Boolean) -> Unit,
    onFullscreenChange: (Boolean) -> Unit,
    onVideoOrientation: (Boolean) -> Unit,
    onOpenExternal: () -> Unit,
    allowExternal: Boolean = true,
    modifier: Modifier = Modifier,
    galleryPagingEnabled: Boolean = false,
) {
    val context = LocalContext.current
    val gestureThresholdPx = with(LocalDensity.current) { 24.dp.toPx() }
    val playButtonHalfPx = with(LocalDensity.current) { 32.dp.toPx() }
    val toolbarHalfPx = with(LocalDensity.current) { 20.dp.toPx() }
    var timelineTop by remember { mutableStateOf<Float?>(null) }
    var videoViewportSize by remember { mutableStateOf(IntSize.Zero) }
    var gestureOrigin by remember { mutableStateOf(Offset.Zero) }
    var toolbarCenterY by remember { mutableStateOf<Float?>(null) }
    var nativePlayCenter by remember { mutableStateOf<Offset?>(null) }
    var videoResizeMode by rememberSaveable { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var forcedAspectRatio by rememberSaveable { mutableStateOf<Float?>(null) }
    var sourceAspectRatio by remember { mutableStateOf(0f) }
    var aspectFeedback by remember { mutableStateOf<String?>(null) }
    var aspectFeedbackRequest by remember { mutableStateOf(0L) }
    var aspectPanelOpen by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(aspectPanelOpen) { aspectPanelOpen = false }
    val aspectModes = listOf(
        "Вписать" to AspectRatioFrameLayout.RESIZE_MODE_FIT,
        "Заполнить с обрезкой" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        "Растянуть" to AspectRatioFrameLayout.RESIZE_MODE_FILL,
    )
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember(context) {
        context.getSharedPreferences("video_playback_positions", Context.MODE_PRIVATE)
    }
    var playbackError by remember(uri) { mutableStateOf<PlaybackException?>(null) }
    var retryGeneration by remember(uri) { mutableStateOf(0) }
    var seekFeedback by remember(uri) { mutableStateOf(SeekFeedbackState()) }
    var adjustmentFeedback by remember(uri) { mutableStateOf<String?>(null) }
    var adjustmentActive by remember(uri) { mutableStateOf(false) }
    var showTimePicker by remember(uri) { mutableStateOf(false) }
    var showPlayerSettings by remember(uri) { mutableStateOf(false) }
    var playableDurationMs by remember(uri) { mutableStateOf(0L) }
    var isPlaying by remember(uri) { mutableStateOf(false) }
    var playbackSpeed by remember(uri) { mutableStateOf(1f) }
    var playbackEnded by remember(uri) { mutableStateOf(false) }
    var scrubPositionMs by remember(uri) { mutableStateOf<Long?>(null) }
    var chromeVisible by remember(uri) { mutableStateOf(true) }
    var unlockVisible by remember(uri) { mutableStateOf(false) }
    var playerView by remember(uri, retryGeneration) { mutableStateOf<PlayerView?>(null) }
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    DisposableEffect(activity, uri) {
        val originalBrightness = activity?.window?.attributes?.screenBrightness
        onDispose {
            if (originalBrightness != null) activity.window.attributes =
                activity.window.attributes.apply { screenBrightness = originalBrightness }
        }
    }

    val player = remember(uri, retryGeneration) {
        val savedPosition = com.domenota.medialoader.data.playback.VideoWatchProgress.resumePosition(prefs, itemId)
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
            setMediaItem(MediaItem.fromUri(uri))
            if (savedPosition > 0L) seekTo(savedPosition)
            prepare()
        }
    }
    fun launchFloatingVideo() {
        if (!allowExternal) return
        try {
            FloatingVideoService.start(context, itemId, uri, player.currentPosition, true)
            player.pause()
            activity?.moveTaskToBack(true)
        } catch (error: Exception) {
            AppLog.e("FloatingVideo", "Could not start floating window", error)
            Toast.makeText(context, "Не удалось открыть всплывающий плеер", Toast.LENGTH_LONG).show()
        }
    }
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()) {
        if (FloatingVideoService.canShow(context)) launchFloatingVideo()
        else Toast.makeText(context, "Разрешите показ поверх других приложений",
            Toast.LENGTH_LONG).show()
    }

    DisposableEffect(player, lifecycleOwner, itemId) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackParametersChanged(parameters: androidx.media3.common.PlaybackParameters) {
                playbackSpeed = parameters.speed
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackError = error
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    sourceAspectRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    val rotatedLandscape = videoSize.width.toFloat() / videoSize.height > 1.05f
                    onVideoOrientation(rotatedLandscape)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                playableDurationMs = player.duration.coerceAtLeast(0L)
                if (playbackState == Player.STATE_ENDED) {
                    playbackEnded = true
                    chromeVisible = true
                    com.domenota.medialoader.data.playback.VideoWatchProgress.record(prefs, itemId, playableDurationMs, playableDurationMs)
                    com.domenota.medialoader.data.playback.VideoWatchProgress.saveResume(prefs, itemId, null)
                } else if (playbackState == Player.STATE_READY) {
                    playbackEnded = false
                    com.domenota.medialoader.data.playback.VideoWatchProgress.record(prefs, itemId, player.currentPosition, playableDurationMs)
                }
            }
        }
        player.addListener(listener)

        fun savePosition() {
            val duration = player.duration
            val position = player.currentPosition.coerceAtLeast(0L)
            com.domenota.medialoader.data.playback.VideoWatchProgress.record(prefs, itemId, position, duration)
            if (player.playbackState == Player.STATE_ENDED ||
                (duration > 0 && position >= duration - 1_500L)
            ) {
                com.domenota.medialoader.data.playback.VideoWatchProgress.saveResume(prefs, itemId, null)
            } else if (position > 1_000L) {
                com.domenota.medialoader.data.playback.VideoWatchProgress.saveResume(prefs, itemId, position)
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    savePosition()
                    player.pause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    val key = "floating_resume_play_$itemId"
                    if (prefs.contains(key)) {
                        player.seekTo(com.domenota.medialoader.data.playback.VideoWatchProgress.resumePosition(prefs, itemId, player.currentPosition))
                        if (prefs.getBoolean(key, false)) player.play() else player.pause()
                        prefs.edit().remove(key).apply()
                    }
                }
                Lifecycle.Event.ON_DESTROY -> savePosition()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            savePosition()
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(seekFeedback.revision) {
        if (seekFeedback.message != null) {
            val revision = seekFeedback.revision
            delay(750L)
            seekFeedback = seekFeedback.clearIfCurrent(revision)
        }
    }
    LaunchedEffect(adjustmentActive) {
        if (!adjustmentActive && adjustmentFeedback != null) {
            delay(900L)
            adjustmentFeedback = null
        }
    }

    LaunchedEffect(fullscreen, controlsLocked, playerView) {
        if (controlsLocked) playerView?.hideController()
        else playerView?.showController()
    }
    LaunchedEffect(controlsLocked) {
        unlockVisible = false
    }
    LaunchedEffect(unlockVisible, controlsLocked) {
        if (controlsLocked && unlockVisible) {
            delay(3_000L)
            unlockVisible = false
        }
    }

    fun seekBy(deltaMs: Long) {
        val last = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, last))
        seekFeedback = seekFeedback.show(deltaMs)
    }

    fun toggleChrome() {
        playerView?.let { view ->
            if (view.isControllerFullyVisible) view.hideController() else view.showController()
        }
    }

    if (showTimePicker) GoToTimeDialog(
        durationMs = playableDurationMs,
        positionMs = player.currentPosition,
        onDismiss = { showTimePicker = false },
        onSeek = { player.seekTo(it); showTimePicker = false },
    )
    if (showPlayerSettings) AlertDialog(
        onDismissRequest = { showPlayerSettings = false },
        title = { Text("Настройки плеера") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            TextButton(onClick = {
                showPlayerSettings = false
                showTimePicker = true
            }, enabled = playableDurationMs > 0L) {
                Icon(Icons.Rounded.AccessTime, contentDescription = null)
                Text("  Перейти ко времени")
            }
            TextButton(onClick = {
                showPlayerSettings = false
                playerView?.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_settings)
                    ?.performClick()
            }) { Text("Настройки дорожек") }
        } },
        confirmButton = { TextButton(onClick = { showPlayerSettings = false }) {
            Text("Закрыть")
        } },
    )

    Box(
        modifier = modifier.background(androidx.compose.ui.graphics.Color.Black)
            .onSizeChanged { videoViewportSize = it },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    playerView = this
                    this.player = player
                    useController = !controlsLocked
                    setControllerAnimationEnabled(false)
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                        chromeVisible = visibility == android.view.View.VISIBLE
                    })
                    setControllerShowTimeoutMs(VideoPlayerUiPolicy.NATIVE_CONTROLLER_TIMEOUT_MS)
                    setShowRewindButton(false)
                    setShowFastForwardButton(false)
                    // Media3 restores the native button's alpha when playback state changes.
                    // Keep its layout slot for adjacent buttons, but draw only our Compose icon.
                    findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause)
                        ?.apply { scaleX = 0f; scaleY = 0f }
                    findViewById<android.view.View>(androidx.media3.ui.R.id.exo_settings)
                        ?.visibility = android.view.View.GONE
                    resizeMode = videoResizeMode
                    findViewById<android.view.View>(androidx.media3.ui.R.id.exo_controller)
                        ?.setPadding(0, 0, 0, 0)
                    viewTreeObserver.addOnGlobalLayoutListener {
                        fun centerInPlayer(child: android.view.View): Offset {
                            var x = child.width / 2f
                            var y = child.height / 2f
                            var node: android.view.View? = child
                            while (node != null && node !== this) {
                                x += node.left - node.scrollX
                                y += node.top - node.scrollY
                                node = node.parent as? android.view.View
                            }
                            return Offset(x, y)
                        }
                        findViewById<android.view.View>(androidx.media3.ui.R.id.exo_progress)
                            ?.takeIf { it.height > 0 }?.let {
                                timelineTop = centerInPlayer(it).y - it.height / 2f
                            }
                        findViewById<android.view.View>(androidx.media3.ui.R.id.exo_position)
                            ?.takeIf { it.height > 0 }?.let { toolbarCenterY = centerInPlayer(it).y }
                        val button = findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause)
                        if (button != null && button.width > 0) {
                            var x = button.width / 2f
                            var y = button.height / 2f
                            var node: android.view.View? = button
                            while (node != null && node !== this) {
                                x += node.left - node.scrollX
                                y += node.top - node.scrollY
                                node = node.parent as? android.view.View
                            }
                            nativePlayCenter = Offset(x, y)
                        }
                    }
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = {
                it.player = player
                it.resizeMode = videoResizeMode
                it.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
                    ?.setAspectRatio(forcedAspectRatio ?: sourceAspectRatio)
                it.useController = !controlsLocked
                it.setControllerShowTimeoutMs(VideoPlayerUiPolicy.NATIVE_CONTROLLER_TIMEOUT_MS)
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Touches above the timeline control chrome; the timeline remains native and draggable.
        if (!controlsLocked) {
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .fillMaxHeight(if (chromeVisible && videoViewportSize.height > 0)
                    ((timelineTop ?: (videoViewportSize.height * 0.60f)) /
                        videoViewportSize.height).coerceIn(0f, 1f) else 1f)
                .pointerInput(Unit) {
                    // Observe the first touch before drag slop, without consuming native taps.
                    awaitEachGesture {
                        gestureOrigin = awaitFirstDown(requireUnconsumed = false,
                            pass = PointerEventPass.Initial).position
                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                            if (event.changes.none { it.pressed }) break
                        }
                    }
                }
                .pointerInput(player, playableDurationMs, activity, audioManager, gestureThresholdPx, videoViewportSize, galleryPagingEnabled, fullscreen) {
                    // Inline gallery horizontal swipes change files. Fullscreen retains player gestures.
                    if (galleryPagingEnabled && !fullscreen) {
                        var mode = VideoGestureMode.NONE
                        var distance = 0f
                        var levelAtStart = 0f
                        detectVerticalDragGestures(
                            onDragStart = { mode = VideoGestureMode.NONE; distance = 0f },
                            onVerticalDrag = { change, amount ->
                                distance += amount
                                if (mode == VideoGestureMode.NONE) {
                                    mode = VideoGesturePolicy.intentionalMode(gestureOrigin.x, 0f, distance,
                                        size.width.toFloat(), gestureThresholdPx, gestureOrigin.y, videoViewportSize.height.toFloat())
                                    levelAtStart = when (mode) {
                                        VideoGestureMode.BRIGHTNESS -> activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f }
                                            ?: runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(.5f)
                                        VideoGestureMode.VOLUME -> audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                                            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                        else -> 0f
                                    }
                                    adjustmentActive = mode != VideoGestureMode.NONE
                                }
                                val level = VideoGesturePolicy.guardedLevel(levelAtStart, distance, size.height.toFloat())
                                when (mode) {
                                    VideoGestureMode.BRIGHTNESS -> {
                                        activity?.window?.let { window -> window.attributes = window.attributes.apply { screenBrightness = level.coerceAtLeast(.02f) } }
                                        adjustmentFeedback = "Яркость ${(level * 100).toInt()}%"
                                    }
                                    VideoGestureMode.VOLUME -> {
                                        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).toInt().coerceIn(0, max), 0)
                                        adjustmentFeedback = "Громкость ${(level * 100).toInt()}%"
                                    }
                                    else -> Unit
                                }
                                if (mode != VideoGestureMode.NONE) change.consume()
                            }, onDragEnd = { adjustmentActive = false }, onDragCancel = { adjustmentActive = false })
                        return@pointerInput
                    }
                    var gestureMode = VideoGestureMode.NONE
                    var startX = 0f
                    var startY = 0f
                    var startPosition = 0L
                    var horizontalDistance = 0f
                    var verticalDistance = 0f
                    var totalX = 0f
                    var totalY = 0f
                    var startLevel = 0f
                    detectDragGestures(
                        onDragStart = { start ->
                            gestureMode = VideoGestureMode.NONE
                            startX = gestureOrigin.x
                            startY = gestureOrigin.y
                            horizontalDistance = 0f
                            verticalDistance = 0f
                            totalX = 0f
                            totalY = 0f
                        },
                        onDrag = { change, dragAmount ->
                            totalX += dragAmount.x
                            totalY += dragAmount.y
                            if (gestureMode == VideoGestureMode.NONE) {
                                gestureMode = VideoGesturePolicy.intentionalMode(startX,
                                    totalX, totalY, size.width.toFloat(), gestureThresholdPx, startY, videoViewportSize.height.toFloat())
                                when (gestureMode) {
                                    VideoGestureMode.SEEK -> {
                                        if (playableDurationMs > 0L) {
                                            chromeVisible = true
                                            startPosition = player.currentPosition.coerceAtLeast(0L)
                                            scrubPositionMs = startPosition
                                            horizontalDistance = totalX
                                            playerView?.showController()
                                        }
                                    }
                                    VideoGestureMode.BRIGHTNESS -> {
                                        startLevel = activity?.window?.attributes?.screenBrightness
                                            ?.takeIf { it >= 0f } ?: runCatching {
                                                Settings.System.getInt(context.contentResolver,
                                                    Settings.System.SCREEN_BRIGHTNESS) / 255f
                                            }.getOrDefault(0.5f)
                                        adjustmentActive = true
                                    }
                                    VideoGestureMode.VOLUME -> {
                                        val maxVolume = audioManager.getStreamMaxVolume(
                                            AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                        startLevel = audioManager.getStreamVolume(
                                            AudioManager.STREAM_MUSIC).toFloat() / maxVolume
                                        adjustmentActive = true
                                    }
                                    VideoGestureMode.NONE -> Unit
                                }
                            }
                            when (gestureMode) {
                                VideoGestureMode.SEEK -> if (playableDurationMs > 0L) {
                                    horizontalDistance = totalX
                                    val target = VideoScrubPolicy.target(startPosition,
                                        horizontalDistance, size.width, playableDurationMs)
                                    scrubPositionMs = target
                                    playerView?.showController()
                                }
                                VideoGestureMode.BRIGHTNESS -> {
                                    verticalDistance = totalY
                                    val level = VideoGesturePolicy.guardedLevel(startLevel,
                                        verticalDistance, size.height.toFloat())
                                    activity?.window?.let { window ->
                                        window.attributes = window.attributes.apply {
                                            screenBrightness = level.coerceAtLeast(0.02f)
                                        }
                                    }
                                    adjustmentFeedback = "Яркость ${((level * 100).toInt())}%"
                                }
                                VideoGestureMode.VOLUME -> {
                                    verticalDistance = totalY
                                    val level = VideoGesturePolicy.guardedLevel(startLevel,
                                        verticalDistance, size.height.toFloat())
                                    val maxVolume = audioManager.getStreamMaxVolume(
                                        AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,
                                        (level * maxVolume).toInt().coerceIn(0, maxVolume), 0)
                                    adjustmentFeedback = "Громкость ${((level * 100).toInt())}%"
                                }
                                VideoGestureMode.NONE -> Unit
                            }
                            change.consume()
                        },
                        onDragEnd = {
                            if (gestureMode == VideoGestureMode.SEEK) {
                                scrubPositionMs?.let(player::seekTo)
                            }
                            scrubPositionMs = null
                            adjustmentActive = false
                        },
                        onDragCancel = {
                            scrubPositionMs = null
                            adjustmentActive = false
                        },
                    )
                }
                .pointerInput(player) {
                    detectTapGestures(
                        onTap = { toggleChrome() },
                        onDoubleTap = { tap ->
                            when {
                                tap.x < size.width * .42f -> seekBy(-10_000L)
                                tap.x > size.width * .58f -> seekBy(10_000L)
                                else -> toggleChrome()
                            }
                        },
                    )
                })
        }
        if (!controlsLocked && chromeVisible) IconButton(
            onClick = {
                if (isPlaying) {
                    player.pause()
                } else {
                    if (VideoPlayerUiPolicy.shouldRestartFromBeginning(playbackEnded)) {
                        player.seekTo(0L)
                        playbackEnded = false
                    }
                    player.play()
                }
            },
            modifier = if (nativePlayCenter != null) Modifier.align(Alignment.TopStart)
                .offset { IntOffset((nativePlayCenter!!.x - playButtonHalfPx).toInt(),
                    (nativePlayCenter!!.y - playButtonHalfPx).toInt()) }.size(64.dp)
            else Modifier.align(Alignment.Center).size(64.dp),
        ) {
            Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(36.dp))
        }
        if (!controlsLocked) scrubPositionMs?.let { position ->
            Text("${formatVideoTime(position)} / ${formatVideoTime(playableDurationMs)}",
                color = androidx.compose.ui.graphics.Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Center).background(
                    androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f),
                    MaterialTheme.shapes.medium).padding(10.dp))
        }
        if (!controlsLocked) seekFeedback.message?.let { feedback ->
            Text(feedback, color = androidx.compose.ui.graphics.Color.White,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.Center).background(
                    androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.65f),
                    MaterialTheme.shapes.medium).padding(10.dp))
        }
        if (!controlsLocked) adjustmentFeedback?.let { feedback ->
            Text(feedback, color = androidx.compose.ui.graphics.Color.White,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.Center).background(
                    androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.65f),
                    MaterialTheme.shapes.medium).padding(10.dp))
        }

        if (!controlsLocked && chromeVisible) Row(
            modifier = Modifier.align(Alignment.TopEnd).offset {
                IntOffset(0, ((toolbarCenterY ?: toolbarHalfPx) - toolbarHalfPx).toInt())
            }.padding(end = 8.dp).height(40.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).pointerInput(videoResizeMode, forcedAspectRatio) {
                detectTapGestures(
                    onLongPress = { aspectPanelOpen = true },
                    onTap = {
                        val current = aspectModes.indexOfFirst { it.second == videoResizeMode }
                        val next = aspectModes[if (forcedAspectRatio != null) 0 else (current + 1) % aspectModes.size]
                        forcedAspectRatio = null
                        videoResizeMode = next.second
                        aspectFeedback = next.first
                        aspectFeedbackRequest++
                    },
                )
            }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.AspectRatio, contentDescription = "Соотношение сторон. Удерживайте для списка",
                    tint = androidx.compose.ui.graphics.Color.White)
            }
            IconButton(onClick = {
                val next = when (playbackSpeed) {
                    in 0f..1.0f -> 1.25f
                    in 1.01f..1.25f -> 1.5f
                    in 1.26f..1.5f -> 2f
                    else -> 1f
                }
                player.setPlaybackSpeed(next)
            }, modifier = Modifier.size(40.dp)) {
                Text("${playbackSpeed}×",
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.labelMedium)
            }
            if (allowExternal && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) IconButton(onClick = {
                if (FloatingVideoService.canShow(context)) launchFloatingVideo()
                else overlayPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")))
            }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.PictureInPictureAlt, contentDescription = "Всплывающий плеер",
                    tint = androidx.compose.ui.graphics.Color.White)
            }
            IconButton(onClick = { showPlayerSettings = true }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.SettingsIcon, contentDescription = "Настройки плеера",
                    tint = androidx.compose.ui.graphics.Color.White)
            }
            IconButton(onClick = { onFullscreenChange(!fullscreen) }, modifier = Modifier.size(40.dp)) {
                Icon(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                    contentDescription = if (fullscreen) "В обычный режим" else "На весь экран",
                    tint = androidx.compose.ui.graphics.Color.White)
            }
        }

        if (!controlsLocked) VideoFormatNotice(aspectFeedback, aspectFeedbackRequest,
            Modifier.align(Alignment.Center).offset(y = (-64).dp))
        if (!controlsLocked && chromeVisible) IconButton(onClick = { onControlsLockedChange(true) },
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).size(48.dp)) {
            Icon(Icons.Rounded.LockOpen, contentDescription = "Заблокировать управление",
                tint = androidx.compose.ui.graphics.Color.White)
        }
        if (controlsLocked) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var tapped = true
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size != 1) tapped = false
                        event.changes.forEach { change ->
                            if ((change.position - down.position).getDistance() >
                                viewConfiguration.touchSlop) tapped = false
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                    if (tapped) unlockVisible = !unlockVisible
                }
            })
            if (unlockVisible) IconButton(onClick = { onControlsLockedChange(false) },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).size(48.dp)) {
                Icon(Icons.Rounded.Lock, contentDescription = "Разблокировать управление",
                    tint = androidx.compose.ui.graphics.Color.White)
            }
        }

        if (aspectPanelOpen) Box(Modifier.fillMaxSize().clickable { aspectPanelOpen = false })
        androidx.compose.animation.AnimatedVisibility(
            visible = aspectPanelOpen,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = androidx.compose.animation.slideInHorizontally { it },
            exit = androidx.compose.animation.slideOutHorizontally { it },
        ) {
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                modifier = Modifier.fillMaxHeight().width(260.dp)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text("Соотношение сторон", style = MaterialTheme.typography.titleMedium)
                    aspectModes.forEach { (label, mode) ->
                        TextButton(onClick = {
                            forcedAspectRatio = null
                            videoResizeMode = mode
                            aspectFeedback = label
                            aspectFeedbackRequest++
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (forcedAspectRatio == null && videoResizeMode == mode) "✓ $label" else label)
                        }
                    }
                    listOf("16:9" to 16f / 9, "4:3" to 4f / 3, "16:10" to 1.6f,
                        "2:1" to 2f, "2.21:1" to 2.21f, "2.35:1" to 2.35f,
                        "2.39:1" to 2.39f, "5:4" to 1.25f).forEach { (label, ratio) ->
                        TextButton(onClick = {
                            forcedAspectRatio = ratio
                            videoResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            aspectFeedback = label
                            aspectFeedbackRequest++
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (forcedAspectRatio == ratio) "✓ $label" else label)
                        }
                    }
                    TextButton(onClick = { aspectPanelOpen = false }, modifier = Modifier.fillMaxWidth()) {
                        Text("Закрыть")
                    }
                }
            }
        }

        playbackError?.let { error ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(28.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                        MaterialTheme.shapes.large,
                    )
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    playbackErrorText(error),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (allowExternal) "Файл можно открыть установленным на устройстве проигрывателем." else "Попробуйте открыть файл заново.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (allowExternal) Button(
                    onClick = onOpenExternal,
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Icon(Icons.Rounded.OpenInNew, contentDescription = null)
                    Text("  Открыть другим приложением")
                }
                TextButton(
                    onClick = {
                        playbackError = null
                        retryGeneration++
                    },
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Text("  Повторить")
                }
            }
        }
    }
}

private fun playbackErrorText(error: PlaybackException): String = when (error.errorCode) {
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED ->
        "Этот формат или кодек пока не поддерживается встроенным проигрывателем"
    else -> "Не удалось воспроизвести видео"
}

private fun formatVideoTime(positionMs: Long): String {
    val seconds = positionMs.coerceAtLeast(0L) / 1000L
    val minutes = seconds / 60L
    return if (minutes >= 60L) "%d:%02d:%02d".format(minutes / 60L, minutes % 60L, seconds % 60L)
    else "%d:%02d".format(minutes, seconds % 60L)
}

@Composable
private fun GoToTimeDialog(durationMs: Long, positionMs: Long, onDismiss: () -> Unit,
    onSeek: (Long) -> Unit) {
    val limit = (durationMs / 1000L).coerceAtLeast(0L)
    val initial = (positionMs / 1000L).coerceIn(0L, limit)
    var hours by remember(durationMs) { mutableStateOf((initial / 3600L).toInt()) }
    var minutes by remember(durationMs) { mutableStateOf(((initial / 60L) % 60L).toInt()) }
    var seconds by remember(durationMs) { mutableStateOf((initial % 60L).toInt()) }
    val maxHours = (limit / 3600L).coerceAtMost(999L).toInt()
    val maxMinutes = if (hours == maxHours) ((limit / 60L) % 60L).toInt() else 59
    val maxSeconds = if (hours == maxHours && minutes == maxMinutes) (limit % 60L).toInt() else 59
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Перейти ко времени") },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                TimeWheel(hours, maxHours, "ч", Modifier.weight(1f)) { value ->
                    hours = value
                    minutes = minutes.coerceAtMost(if (value == maxHours) ((limit / 60L) % 60L).toInt() else 59)
                    seconds = seconds.coerceAtMost(if (value == maxHours && minutes == ((limit / 60L) % 60L).toInt())
                        (limit % 60L).toInt() else 59)
                }
                TimeWheel(minutes, maxMinutes, "мин", Modifier.weight(1f)) { value ->
                    minutes = value
                    seconds = seconds.coerceAtMost(if (hours == maxHours && value == maxMinutes)
                        (limit % 60L).toInt() else 59)
                }
                TimeWheel(seconds, maxSeconds, "с", Modifier.weight(1f)) { seconds = it }
            }
        },
        confirmButton = { TextButton(onClick = {
            onSeek(((hours * 3600L + minutes * 60L + seconds) * 1000L).coerceAtMost(durationMs))
        }) { Text("Перейти") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun TimeWheel(value: Int, maximum: Int, label: String, modifier: Modifier,
    onValueChange: (Int) -> Unit) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        AndroidView(factory = { context -> NumberPicker(context).apply {
            minValue = 0
            maxValue = if (maximum == 1) 3 else maximum
            if (maximum == 1) displayedValues = arrayOf("0", "1", "0", "1")
            this.value = value.coerceAtMost(maximum)
            wrapSelectorWheel = maximum > 0
            if (Build.VERSION.SDK_INT >= 29) setTextColor(textColor)
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
            setOnValueChangedListener { _, _, new ->
                onValueChange(if (maximum == 1) new % 2 else new)
            }
        } }, update = { picker ->
            if (Build.VERSION.SDK_INT >= 29) picker.setTextColor(textColor)
            val pickerMaximum = if (maximum == 1) 3 else maximum
            if (picker.maxValue != pickerMaximum) {
                picker.displayedValues = null
                picker.maxValue = pickerMaximum
                if (maximum == 1) picker.displayedValues = arrayOf("0", "1", "0", "1")
            }
            picker.wrapSelectorWheel = maximum > 0
            if ((if (maximum == 1) picker.value % 2 else picker.value) != value)
                picker.value = value.coerceAtMost(maximum)
        }, modifier = Modifier.size(width = 84.dp, height = 150.dp))
    }
}

internal fun applyFullscreen(
    activity: Activity,
    decorView: android.view.View,
    fullscreen: Boolean,
    landscapeVideo: Boolean,
) {
    val window = activity.window
    val controller = WindowInsetsControllerCompat(window, decorView)
    if (fullscreen) {
        activity.requestedOrientation = if (landscapeVideo) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        com.domenota.medialoader.ui.theme.edgeToEdgeWindow(window, dark = true)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        com.domenota.medialoader.ui.theme.edgeToEdgeWindow(window,
            dark = com.domenota.medialoader.ui.theme.appThemeIsDark(activity))
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun PhotoPager(
    startItem: DownloadEntity,
    queue: List<DownloadEntity>,
    onCurrentItemChanged: (DownloadEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = remember(startItem.id, queue) {
        val valid = queue
            .filter { it.mediaType == MediaType.PHOTO && !it.savedUri.isNullOrBlank() }
            .distinctBy { it.id }
        if (valid.any { it.id == startItem.id }) valid else listOf(startItem) + valid
    }
    val initialPage = remember(startItem.id, items) {
        items.indexOfFirst { it.id == startItem.id }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { items.size.coerceAtLeast(1) },
    )
    var currentZoomed by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.settledPage, items) {
        currentZoomed = false
        items.getOrNull(pagerState.settledPage)?.let(onCurrentItemChanged)
    }

    HorizontalPager(
        state = pagerState,
        userScrollEnabled = !currentZoomed,
        key = { page -> items[page].id },
        modifier = modifier.background(androidx.compose.ui.graphics.Color.Black),
    ) { page ->
        val photo = items[page]
        val photoUri = photo.savedUri?.let(Uri::parse)
        if (photoUri == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Файл недоступен", color = androidx.compose.ui.graphics.Color.White)
            }
        } else {
            ZoomablePhoto(
                item = photo,
                uri = photoUri,
                position = page + 1,
                count = items.size,
                onZoomedChanged = { zoomed ->
                    if (pagerState.currentPage == page) currentZoomed = zoomed
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private data class DecodedImage(
    val bitmap: Bitmap,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

@Composable
internal fun ZoomablePhoto(
    item: DownloadEntity,
    uri: Uri,
    position: Int,
    count: Int,
    onZoomedChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    showMetadata: Boolean = true,
    onTap: () -> Unit = {},
) {
    val context = LocalContext.current
    var decoded by remember(uri) { mutableStateOf<DecodedImage?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }
    var scale by remember(uri) { mutableFloatStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }
    var containerSize by remember(uri) { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(uri) {
        val result = withContext(Dispatchers.IO) {
            runCatching { decodeLocalImage(context, uri) }
        }
        decoded = result.getOrNull()
        failed = result.isFailure || decoded == null
    }

    LaunchedEffect(scale) {
        onZoomedChanged(scale > 1.01f)
    }

    Box(
        modifier = modifier
            .background(androidx.compose.ui.graphics.Color.Black)
            .onSizeChanged { size ->
                containerSize = size
                val image = decoded
                if (image != null) {
                    offset = clampPhotoOffset(offset, scale, size, image)
                }
            }
            .pointerInput(uri, decoded, containerSize) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        val image = decoded ?: return@detectTapGestures
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            offset = clampPhotoOffset(Offset.Zero, scale, containerSize, image)
                        }
                    },
                )
            }
            .pointerInput(uri, decoded, containerSize) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var transformActive = scale > 1.01f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        if (pressed.size >= 2) transformActive = true
                        if (!transformActive) {
                            // Keep a one-finger swipe unconsumed so HorizontalPager can page.
                            continue
                        }

                        val image = decoded ?: continue
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val nextScale = (scale * zoom).coerceIn(1f, 5f)
                        val proposed = if (nextScale <= 1.01f) Offset.Zero else offset + pan
                        scale = nextScale
                        offset = clampPhotoOffset(proposed, nextScale, containerSize, image)
                        event.changes.forEach { change ->
                            if (change.pressed || change.previousPressed) change.consume()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            decoded != null -> {
                val image = requireNotNull(decoded)
                Image(
                    bitmap = image.bitmap.asImageBitmap(),
                    contentDescription = item.originalName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )

                if (showMetadata) Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.56f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "${image.sourceWidth} × ${image.sourceHeight} · ${formatMediaSize(item.sizeBytes)}",
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (count > 1) {
                        Text(
                            "$position / $count",
                            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.78f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            failed -> Text(
                "Не удалось открыть изображение",
                color = androidx.compose.ui.graphics.Color.White,
            )
            else -> CircularProgressIndicator()
        }
    }
}

private fun clampPhotoOffset(
    proposed: Offset,
    scale: Float,
    container: IntSize,
    image: DecodedImage,
): Offset {
    if (scale <= 1.01f || container.width <= 0 || container.height <= 0) return Offset.Zero

    val fitScale = min(
        container.width.toFloat() / image.sourceWidth.coerceAtLeast(1),
        container.height.toFloat() / image.sourceHeight.coerceAtLeast(1),
    )
    val displayedWidth = image.sourceWidth * fitScale * scale
    val displayedHeight = image.sourceHeight * fitScale * scale
    val maxX = max(0f, (displayedWidth - container.width) / 2f)
    val maxY = max(0f, (displayedHeight - container.height) / 2f)

    return Offset(
        x = proposed.x.coerceIn(-maxX, maxX),
        y = proposed.y.coerceIn(-maxY, maxY),
    )
}

private fun formatMediaSize(bytes: Long?): String {
    val value = bytes ?: return "размер неизвестен"
    val kb = value / 1024.0
    return when {
        value < 1024L -> "$value Б"
        kb < 1024.0 -> String.format(java.util.Locale.US, "%.1f КБ", kb)
        kb < 1024.0 * 1024.0 ->
            String.format(java.util.Locale.US, "%.1f МБ", kb / 1024.0)
        else -> String.format(java.util.Locale.US, "%.2f ГБ", kb / 1024.0 / 1024.0)
    }
}

private fun decodeLocalImage(
    context: android.content.Context,
    uri: Uri,
    maxSide: Int = 4096,
): DecodedImage? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, options)
    } ?: return null
    return DecodedImage(
        bitmap = bitmap,
        sourceWidth = bounds.outWidth,
        sourceHeight = bounds.outHeight,
    )
}

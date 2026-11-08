package com.domenota.medialoader.ui.screens.gallery

import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.core.view.WindowInsetsControllerCompat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.app.WallpaperManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.core.view.WindowInsetsCompat
import java.util.Locale
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.outlined.*
import com.domenota.medialoader.ui.screens.rememberVideoViewerPoster
import com.domenota.medialoader.ui.screens.VideoViewerPoster
import androidx.compose.material3.*
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton
import com.domenota.medialoader.ui.components.VideoSongSearchDialog
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.domenota.medialoader.core.database.*
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.ui.screens.LocalVideoPlayer
import com.domenota.medialoader.ui.screens.VideoPlayerUiPolicy
import com.domenota.medialoader.ui.screens.ZoomablePhoto
import com.domenota.medialoader.ui.screens.applyFullscreen
import com.domenota.medialoader.ui.screens.findActivity
import com.domenota.medialoader.ui.components.DownloadThumbnail
import com.domenota.medialoader.ui.model.toUi
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun GalleryMediaViewer(
    item: DownloadEntity, media: List<DownloadEntity>, onBack: () -> Unit,
    onShare: (DownloadEntity) -> Unit, onEdit: (DownloadEntity) -> Unit, onDelete: (DownloadEntity) -> Unit,
    favoriteIds: Set<String>, onFavorite: suspend (String, Boolean) -> Unit,
    folders: List<CatalogFolderEntity>, onSetFolder: suspend (String, String?) -> Unit,
    onMoveToPrivate: (DownloadEntity) -> Unit, onOpenExternal: (DownloadEntity) -> Unit,
    onCurrentItemChanged: (DownloadEntity) -> Unit,
) {
    val photoCache = remember { com.domenota.medialoader.ui.screens.PhotoPreviewCache() }
    DisposableEffect(photoCache) { onDispose { photoCache.clear() } }
    val queue = remember(media, item) { GalleryViewerState.queue(media.ifEmpty { listOf(item) }.map { it.id }, media.ifEmpty { listOf(item) }) }
    if (queue.isEmpty()) { LaunchedEffect(Unit) { onBack() }; return }
    var currentId by rememberSaveable { mutableStateOf(item.id) }
    val ids = queue.map { it.id }
    var previousIds by remember { mutableStateOf(ids) }
    val pager = rememberPagerState(initialPage = ids.indexOf(currentId).coerceAtLeast(0), pageCount = { queue.size })
    var chrome by rememberSaveable { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    val current = queue.getOrNull(pager.currentPage) ?: queue.first()
    // Keep fullscreen when navigating episodes through the player arrows.
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var locked by rememberSaveable(current.id) { mutableStateOf(false) }
    var landscape by rememberSaveable(current.id) { mutableStateOf(false) }
    var menu by remember(current.id) { mutableStateOf(false) }
    var wallpaperChoice by remember(current.id) { mutableStateOf(false) }
    var photoTool by remember(current.id) { mutableStateOf<String?>(null) }
    var loopVideo by rememberSaveable(current.id) { mutableStateOf(false) }
    var videoSpeed by rememberSaveable(current.id) { mutableStateOf(1f) }
    var floatingRequest by remember(current.id) { mutableIntStateOf(0) }
    var playerCommand by remember(current.id) { mutableStateOf<Pair<Int, String>?>(null) }
    var playerCommandSeq by remember(current.id) { mutableIntStateOf(0) }
    var songSearch by remember(current.id) { mutableStateOf(false) }
    var info by remember(current.id) { mutableStateOf(false) }
    var chooseFolder by remember(current.id) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val videoTypes = remember(queue) { queue.map { it.mediaType } }
    val showEpisodeArrows = remember(videoTypes) { videoTypes.count { it == MediaType.VIDEO } > 1 }
    val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        Configuration.ORIENTATION_LANDSCAPE
    var episodeNavigation by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    fun navigateVideo(targetIndex: Int, keepPlaying: Boolean) {
        val target = queue.getOrNull(targetIndex) ?: return
        episodeNavigation = target.id to keepPlaying
        scope.launch { pager.animateScrollToPage(targetIndex) }
    }
    fun mutate(action: suspend () -> Unit) { scope.launch {
        try { action(); error = null } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Не удалось выполнить действие" }
    } }
    LaunchedEffect(ids) {
        val target = GalleryViewerState.neighbor(currentId, previousIds, ids.toSet()) ?: ids.first()
        val targetIndex = ids.indexOf(target).coerceAtLeast(0)
        pager.scrollToPage(targetIndex)
        currentId = target
        zoomed = false
        onCurrentItemChanged(queue[targetIndex])
        previousIds = ids
    }
    LaunchedEffect(pager.settledPage, pager.isScrollInProgress) {
        if (!pager.isScrollInProgress) queue.getOrNull(pager.settledPage)?.let {
            if (episodeNavigation?.first != it.id) episodeNavigation = null
            currentId = it.id; zoomed = false; onCurrentItemChanged(it)
        }
    }
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }
    // Gallery alone owns transparent system bars while open. The controls'
    // gradient fills behind the status/navigation indicators, avoiding the
    // dark rectangular bands shown by the previous build on Xiaomi.
    DisposableEffect(activity) {
        val window = activity?.window
        val initialRequestedOrientation = activity?.requestedOrientation
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        @Suppress("DEPRECATION")
        val oldStatusColor = window?.statusBarColor
        @Suppress("DEPRECATION")
        val oldNavColor = window?.navigationBarColor
        val oldStatusIcons = controller?.isAppearanceLightStatusBars
        val oldNavIcons = controller?.isAppearanceLightNavigationBars
        val oldBarsBehavior = controller?.systemBarsBehavior
        val oldStatusContrast = if (Build.VERSION.SDK_INT >= 29) window?.isStatusBarContrastEnforced else null
        val oldNavContrast = if (Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced else null
        onDispose {
            // The viewer's rotate button must not strand the Gallery in
            // landscape when the video is closed.
            if (activity != null && initialRequestedOrientation != null &&
                activity.requestedOrientation != initialRequestedOrientation) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            if (window != null && controller != null) {
                @Suppress("DEPRECATION")
                if (oldStatusColor != null) window.statusBarColor = oldStatusColor
                @Suppress("DEPRECATION")
                if (oldNavColor != null) window.navigationBarColor = oldNavColor
                if (oldStatusIcons != null) controller.isAppearanceLightStatusBars = oldStatusIcons
                if (oldNavIcons != null) controller.isAppearanceLightNavigationBars = oldNavIcons
                if (oldBarsBehavior != null) controller.systemBarsBehavior = oldBarsBehavior
                if (Build.VERSION.SDK_INT >= 29) {
                    if (oldStatusContrast != null) window.isStatusBarContrastEnforced = oldStatusContrast
                    if (oldNavContrast != null) window.isNavigationBarContrastEnforced = oldNavContrast
                }
                // Never leave Android navigation hidden after exiting gallery.
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    SideEffect {
        if (activity != null && !fullscreen) {
            val window = activity.window
            @Suppress("DEPRECATION")
            window.statusBarColor = AndroidColor.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = AndroidColor.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 29) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            val bars = WindowInsetsControllerCompat(window, window.decorView)
            // Landscape is always status-bar free, including when controls or More are visible.
            bars.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (isLandscape) {
                bars.isAppearanceLightStatusBars = false
                bars.isAppearanceLightNavigationBars = false
                bars.hide(WindowInsetsCompat.Type.statusBars())
                // Keep 3-button navigation visible while popups stay inside its safe inset.
                bars.show(WindowInsetsCompat.Type.navigationBars())
            } else if (chrome && !locked) {
                bars.isAppearanceLightStatusBars = false
                bars.isAppearanceLightNavigationBars = false
                bars.show(WindowInsetsCompat.Type.systemBars())
            } else {
                bars.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    DisposableEffect(fullscreen, landscape, activity, view) {
        if (activity != null && fullscreen) applyFullscreen(activity, view, true, landscape)
        onDispose { if (activity != null && fullscreen) applyFullscreen(activity, view, false, landscape) }
    }
    BackHandler(fullscreen) { fullscreen = false }
    // Photo and video share one edge-to-edge viewport. Chrome is drawn on top,
    // never as Scaffold bars that reduce the image size or leave blank bands.
    Box(Modifier.fillMaxSize().background(Color.Black)) {
            // Pager may ask its previous key provider for one new index during a size update.
            HorizontalPager(state = pager, key = { page -> queue.getOrNull(page)?.id ?: "pending-gallery-page:$page" }, beyondViewportPageCount = 1,
                userScrollEnabled = !zoomed && !fullscreen && !locked,
                modifier = Modifier.fillMaxSize()) { page ->
                val row = queue.getOrNull(page) ?: return@HorizontalPager
                val uri = Uri.parse(requireNotNull(row.savedUri))
                val poster = if (row.mediaType == MediaType.VIDEO) rememberVideoViewerPoster(row.id, uri) else null
                if (row.mediaType == MediaType.PHOTO) key(row.id) {
                    ZoomablePhoto(row, uri, page + 1, queue.size,
                        onZoomedChanged = { if (page == pager.currentPage) zoomed = it }, modifier = Modifier.fillMaxSize(),
                        showMetadata = false, onTap = { chrome = !chrome }, photoCache = photoCache, active = page == pager.settledPage)
                } else if (GalleryViewerState.canCreateVideo(page, pager.settledPage, pager.isScrollInProgress)) key(row.id) {
                    val previousVideo = VideoPlayerUiPolicy.adjacentVideoIndex(videoTypes, page, -1)
                    val nextVideo = VideoPlayerUiPolicy.adjacentVideoIndex(videoTypes, page, 1)
                    val fromSkipButton = episodeNavigation?.first == row.id
                    // Manual swipes stay paused as before; the arrows preserve play/pause intent.
                    LocalVideoPlayer(row.id, uri, fullscreen, locked, { locked = it }, { fullscreen = it }, { landscape = it },
                        { onOpenExternal(row) }, allowExternal = true, modifier = Modifier.fillMaxSize(),
                        galleryPagingEnabled = true, initialPoster = poster, repeatOne = loopVideo,
                        requestedSpeed = videoSpeed, onPlaybackSpeedChanged = { videoSpeed = it },
                        showAlbumSkipButton = showEpisodeArrows,
                        onPreviousVideo = previousVideo?.let { target ->
                            { playing: Boolean -> navigateVideo(target, playing) }
                        },
                        onNextVideo = nextVideo?.let { target ->
                            { playing: Boolean -> navigateVideo(target, playing) }
                        },
                        startAtBeginning = fromSkipButton,
                        autoplayOnNavigation = fromSkipButton && episodeNavigation?.second == true,
                        onControlsVisibilityChanged = { shown ->
                            if (page == pager.currentPage) chrome = shown
                        },
                        floatingWindowRequest = if (page == pager.currentPage) floatingRequest else 0,
                        galleryCommand = if (page == pager.currentPage) playerCommand else null,
                    )
                } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (poster != null) VideoViewerPoster(poster, Modifier.fillMaxSize())
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(48.dp), Color.White)
                }
            }
        // Draw translucent overlays AFTER the pager, so the artwork occupies
        // the same full-screen rectangle whether controls are visible or hidden.
        if (chrome && !fullscreen && !locked) {
            val created = Instant.ofEpochMilli(current.createdAtEpochMillis)
                .atZone(ZoneId.systemDefault())
            Box(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .then(if (isLandscape && current.mediaType == MediaType.VIDEO)
                        Modifier.background(Color.Black.copy(alpha = .42f))
                    else Modifier.background(Brush.verticalGradient(listOf(
                        viewerHeaderOverlayColor, Color.Black.copy(alpha = .28f), Color.Transparent))))
                    .then(if (isLandscape) Modifier else Modifier.statusBarsPadding()),
            ) {
                val onRotate: () -> Unit = {
                    // SENSOR_LANDSCAPE lets the player follow BOTH landscape sides.
                    // MainActivity already handles orientation|screenSize changes
                    // without discarding the active player and playback position.
                    activity?.requestedOrientation = galleryViewerRequestedOrientation(isLandscape)
                }
                if (current.mediaType == MediaType.VIDEO) {
                    GalleryVideoHeader(
                        date = created.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ru"))),
                        time = created.format(DateTimeFormatter.ofPattern("HH:mm")),
                        landscape = isLandscape,
                        onBack = onBack,
                        onRotate = onRotate,
                        onFloating = { floatingRequest++ },
                        onShare = { onShare(current) },
                        onEdit = { onEdit(current) },
                        onFavorite = { mutate { onFavorite(current.id, current.id !in favoriteIds) } },
                        onDelete = { onDelete(current) },
                        onMenu = { menu = true },
                    )
                } else {
                    GalleryV10Header(
                        date = created.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ru"))),
                        time = created.format(DateTimeFormatter.ofPattern("HH:mm")),
                        onBack = onBack, onRotate = onRotate,
                    )
                }
            }
            // Landscape video uses top-row actions and one timeline in the
            // player. Portrait photos/videos keep the familiar bottom actions.
            if (current.mediaType != MediaType.VIDEO || !isLandscape) {
                Box(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent,
                            Color.Black.copy(alpha = .26f), viewerBottomOverlayColor)))
                        .navigationBarsPadding(),
                ) {
                    GalleryV10Bottom(
                        isPhoto = current.mediaType == MediaType.PHOTO,
                        isFavorite = current.id in favoriteIds,
                        onShare = { onShare(current) },
                        onEdit = { onEdit(current) },
                        onFavorite = { mutate { onFavorite(current.id, current.id !in favoriteIds) } },
                        onDelete = { onDelete(current) },
                        onMenu = { menu = true },
                    )
                }
            }

        }
        if (menu) GalleryV10Popup(
            isPhoto = current.mediaType == MediaType.PHOTO,
            repeatOne = loopVideo,
            speed = videoSpeed,
            allowPrivate = current.providerId != "device",
            onDismiss = { menu = false },
            onSpeed = { selected -> videoSpeed = selected; menu = false },
            onAction = { action ->
                if (action == "loop") {
                    loopVideo = !loopVideo
                } else {
                    menu = false
                    when (action) {
                        "share" -> onShare(current)
                        "edit" -> onEdit(current)
                        "favorite" -> mutate { onFavorite(current.id, current.id !in favoriteIds) }
                        "delete" -> onDelete(current)
                        "aspect", "time", "tracks" -> {
                            playerCommandSeq++
                            playerCommand = playerCommandSeq to action
                        }
                        "album" -> chooseFolder = true
                        "wallpaper" -> wallpaperChoice = true
                        "info" -> info = true
                        "ocr", "cutout", "watermark" -> photoTool = action
                        "edit" -> onEdit(current)
                        "cast" -> mutate { galleryCast(context, current) }
                        "song" -> songSearch = true
                        "copy" -> mutate { copyGalleryFile(context, current) }
                        "open" -> onOpenExternal(current)
                        "private" -> if (current.providerId != "device") onMoveToPrivate(current)
                    }
                }
            },
        )
        error?.let { message -> Snackbar(Modifier.align(Alignment.BottomCenter).padding(12.dp),
            action = { TextButton({ error = null }) { Text("Закрыть") } }) { Text(message) } }
    }
    if (wallpaperChoice) {
        val wallpaperItem = current
        Dialog(onDismissRequest = { wallpaperChoice = false }) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF262626),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Применить обои к", color = Color(0xFFAAAAAA),
                        fontSize = 15.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    listOf(
                        "Экран блокировки" to WallpaperManager.FLAG_LOCK,
                        "Рабочий стол" to WallpaperManager.FLAG_SYSTEM,
                        "Оба" to (WallpaperManager.FLAG_LOCK or WallpaperManager.FLAG_SYSTEM),
                    ).forEach { (label, flag) ->
                        Box(
                            Modifier.fillMaxWidth().height(64.dp)
                                .clickable {
                                    wallpaperChoice = false
                                    mutate { galleryApplyWallpaper(context, wallpaperItem, flag) }
                                }, contentAlignment = Alignment.Center,
                        ) {
                            Text(label, color = Color.White, fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { wallpaperChoice = false },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A4A4A)),
                    ) { Text("Отмена", color = Color.White) }
                }
            }
        }
    }
    photoTool?.let { mode -> PhotoToolsDialog(current, mode) { photoTool = null } }
    if (songSearch) VideoSongSearchDialog(current.savedUri.orEmpty()) { songSearch = false }
    if (chooseFolder) AlertDialog(onDismissRequest = { chooseFolder = false }, title = { Text("Добавить в альбом") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            TextButton({ mutate { onSetFolder(current.id, null); chooseFolder = false } }) { Text("Без папки") }
            folders.forEach { folder -> TextButton({ mutate { onSetFolder(current.id, folder.id); chooseFolder = false } }) { Text(folder.name) } }
        } }, confirmButton = { TextButton({ chooseFolder = false }) { Text("Закрыть") } })
    if (info) {
        var dimensions by remember(current.id) { mutableStateOf("Читаем свойства…") }
        LaunchedEffect(current.id) { dimensions = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(current.savedUri)
                if (current.mediaType == MediaType.PHOTO) {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    if (options.outWidth > 0) "${options.outWidth} × ${options.outHeight}" else "Размеры недоступны"
                } else {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        "${retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "?"} × ${retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "?"}"
                    } finally { retriever.release() }
                }
            }.getOrDefault("Размеры недоступны")
        } }
        AlertDialog(onDismissRequest = { info = false }, title = { Text("Сведения") }, text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(current.originalName); Text(dimensions)
                Text(current.sizeBytes?.let { android.text.format.Formatter.formatFileSize(context, it) } ?: "Размер файла неизвестен")
            }
        }, confirmButton = { TextButton({ info = false }) { Text("Закрыть") } })
    }
}

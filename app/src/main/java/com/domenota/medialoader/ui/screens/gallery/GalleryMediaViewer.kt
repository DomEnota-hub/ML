package com.domenota.medialoader.ui.screens.gallery

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.core.database.*
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.ui.screens.LocalVideoPlayer
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
    val queue = remember(media, item) { GalleryViewerState.queue(media.ifEmpty { listOf(item) }.map { it.id }, media.ifEmpty { listOf(item) }) }
    if (queue.isEmpty()) { LaunchedEffect(Unit) { onBack() }; return }
    var currentId by rememberSaveable { mutableStateOf(item.id) }
    val ids = queue.map { it.id }
    var previousIds by remember { mutableStateOf(ids) }
    val pager = rememberPagerState(initialPage = ids.indexOf(currentId).coerceAtLeast(0), pageCount = { queue.size })
    var chrome by rememberSaveable { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    val current = queue.getOrNull(pager.currentPage) ?: queue.first()
    var fullscreen by rememberSaveable(current.id) { mutableStateOf(false) }
    var locked by rememberSaveable(current.id) { mutableStateOf(false) }
    var landscape by rememberSaveable(current.id) { mutableStateOf(false) }
    var menu by remember(current.id) { mutableStateOf(false) }
    var info by remember(current.id) { mutableStateOf(false) }
    var confirmDelete by remember(current.id) { mutableStateOf(false) }
    var chooseFolder by remember(current.id) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
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
            currentId = it.id; zoomed = false; onCurrentItemChanged(it)
        }
    }
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(fullscreen, landscape, activity, view) {
        if (activity != null && fullscreen) applyFullscreen(activity, view, true, landscape)
        onDispose { if (activity != null && fullscreen) applyFullscreen(activity, view, false, landscape) }
    }
    BackHandler(fullscreen) { fullscreen = false }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Scaffold(containerColor = Color.Black, contentColor = Color.White, contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                if (chrome && !fullscreen && !locked) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", tint = Color.White) }
                    val date = Instant.ofEpochMilli(current.createdAtEpochMillis).atZone(ZoneId.systemDefault())
                    Column(Modifier.weight(1f).padding(start = 5.dp)) {
                        Text(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)), fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(date.format(DateTimeFormatter.ofPattern("HH:mm")), fontSize = 11.sp, color = Color(0xFFAAAAAA))
                    }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Действия с файлом", tint = Color.White) }
                    }
                }
            }, bottomBar = {
                if (chrome && !fullscreen && !locked) Row(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ onShare(current) }) { Icon(Icons.Rounded.IosShare, "Поделиться", tint = Color.White) }
                    IconButton({ onEdit(current) }) { Icon(Icons.Rounded.Edit, "Редактировать", tint = Color.White) }
                    IconButton({ mutate { onFavorite(current.id, current.id !in favoriteIds) } }) {
                        Icon(if (current.id in favoriteIds) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Избранное",
                            tint = if (current.id in favoriteIds) MaterialTheme.colorScheme.primary else Color.White)
                    }
                    IconButton({ confirmDelete = true }) { Icon(Icons.Rounded.DeleteOutline, "Удалить", tint = MaterialTheme.colorScheme.error) }
                    IconButton({ menu = true }) { Icon(Icons.Rounded.MoreHoriz, "Ещё", tint = Color.White) }
                }
            }) { padding ->
            // Pager may ask its previous key provider for one new index during a size update.
            HorizontalPager(state = pager, key = { page -> queue.getOrNull(page)?.id ?: "pending-gallery-page:$page" }, beyondViewportPageCount = 0,
                userScrollEnabled = !zoomed && !fullscreen && !locked,
                modifier = Modifier.fillMaxSize().padding(padding)) { page ->
                val row = queue.getOrNull(page) ?: return@HorizontalPager
                val uri = Uri.parse(requireNotNull(row.savedUri))
                if (row.mediaType == MediaType.PHOTO) key(row.id, pager.settledPage) {
                    ZoomablePhoto(row, uri, page + 1, queue.size,
                        onZoomedChanged = { if (page == pager.currentPage) zoomed = it }, modifier = Modifier.fillMaxSize(),
                        showMetadata = false, onTap = { chrome = !chrome })
                } else if (GalleryViewerState.canCreateVideo(page, pager.settledPage, pager.isScrollInProgress)) key(row.id) {
                    // ExoPlayer starts with playWhenReady=false; only its play button starts playback.
                    LocalVideoPlayer(row.id, uri, fullscreen, locked, { locked = it }, { fullscreen = it }, { landscape = it },
                        { onOpenExternal(row) }, allowExternal = true, modifier = Modifier.fillMaxSize(), galleryPagingEnabled = true)
                } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    DownloadThumbnail(row.savedUri, row.previewUrl, row.toUi().kind, Modifier.fillMaxSize())
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(48.dp), Color.White)
                }
            }
        }
        error?.let { message -> Snackbar(Modifier.align(Alignment.BottomCenter).padding(12.dp),
            action = { TextButton({ error = null }) { Text("Закрыть") } }) { Text(message) } }
    }
    if (menu) ModalBottomSheet(onDismissRequest = { menu = false }) {
        com.domenota.medialoader.ui.theme.TransparentWindowNavigation()
        Column(Modifier.fillMaxWidth().padding(horizontal = 17.dp).padding(bottom = 24.dp)) {
            Text("Действия с файлом", fontSize = 18.sp, modifier = Modifier.padding(bottom = 12.dp))
            TextButton({ menu = false; info = true }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Info, null); Spacer(Modifier.width(12.dp)); Text("Сведения") }
            TextButton({ menu = false; chooseFolder = true }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(12.dp)); Text("Добавить в альбом") }
            TextButton({ menu = false; onOpenExternal(current) }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.OpenInNew, null); Spacer(Modifier.width(12.dp)); Text("Открыть другим приложением") }
            if (current.providerId != "device") TextButton({ menu = false; onMoveToPrivate(current) }, Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Lock, null); Spacer(Modifier.width(12.dp)); Text("Переместить в личное")
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Удалить файл?") },
        text = { Text(current.originalName) }, confirmButton = { TextButton({ confirmDelete = false; onDelete(current) }) {
            Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error)
            Text("Удалить", color = MaterialTheme.colorScheme.error)
        } }, dismissButton = { TextButton({ confirmDelete = false }) { Text("Отмена") } })
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

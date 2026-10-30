package com.domenota.medialoader.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.core.database.*
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.*
import com.domenota.medialoader.ui.components.*
import com.domenota.medialoader.ui.model.toUi
import com.domenota.medialoader.ui.screens.gallery.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun GalleryModeScreen(
    items: List<DownloadEntity>, source: LibrarySource, loading: Boolean, message: String?,
    folders: List<CatalogFolderEntity>, assignments: List<CatalogAssignmentEntity>, header: @Composable () -> Unit,
    onOpen: (String, List<String>) -> Unit, onOpenPrivate: () -> Unit,
    onFavorite: suspend (String, Boolean) -> Unit, onSetFolder: suspend (String, String?) -> Unit,
    onCreateFolder: suspend (String) -> String, onShare: (String) -> Unit, onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit, onMoveToPrivate: (String) -> Unit,
    searchVisible: Boolean? = null, tabOverride: Int? = null, onTabChange: (Int) -> Unit = {},
    deviceAlbums: Map<String, DeviceAlbum> = emptyMap(),
    onDetailChange: (Boolean) -> Unit = {}, onSettings: (() -> Unit)? = null,
    onDeleteSelected: ((List<String>) -> Unit)? = null,
) {
    var search by rememberSaveable(source.name) { mutableStateOf("") }
    var localTab by rememberSaveable { mutableIntStateOf(0) }
    var selection by rememberSaveable(source.name) { mutableStateOf("all") }
    var selectionTitle by rememberSaveable(source.name) { mutableStateOf("") }
    var folderTarget by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<DownloadEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var detailMenu by remember { mutableStateOf(false) }
    val detailOpen = selectionTitle.isNotEmpty()
    LaunchedEffect(detailOpen) { onDetailChange(detailOpen) }
    DisposableEffect(Unit) { onDispose { onDetailChange(false) } }
    val tab = tabOverride ?: localTab
    fun selectTab(value: Int) { localTab = value; onTabChange(value) }
    fun openAlbum(key: String, title: String) { selection = key; selectionTitle = title; selectTab(0) }
    fun closeAlbum() { selection = "all"; selectionTitle = ""; selectTab(1) }
    BackHandler(detailOpen || tab != 0) { if (detailOpen) closeAlbum() else selectTab(0) }
    LaunchedEffect(searchVisible) { if (searchVisible == false) search = "" }
    var selectedIds by rememberSaveable(source.name, selection, search, tab, stateSaver = MediaSelectionState.saver) { mutableStateOf(arrayListOf<String>()) }
    val selecting = selectedIds.isNotEmpty()
    fun toggle(id: String) { selectedIds = ArrayList(if (id in selectedIds) selectedIds - id else selectedIds + id) }
    BackHandler(selecting) { selectedIds = arrayListOf() }
    val scope = rememberCoroutineScope()
    fun mutate(action: suspend () -> Unit) { scope.launch {
        try { action(); error = null } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Не удалось выполнить действие" }
    } }
    val catalog = remember(assignments) { assignments.associateBy { it.downloadId } }
    val buckets = remember(items, source, deviceAlbums) {
        if (source == LibrarySource.APP) emptyMap() else if (deviceAlbums.isNotEmpty()) deviceAlbums
        else items.mapNotNull { row -> row.groupId?.let { row.id to DeviceAlbum(it, it) } }.toMap()
    }
    val media = remember(items, assignments, buckets) { GalleryLibraryState.filter(items, assignments, "all", "", buckets) }
    val displayed = remember(items, assignments, selection, search, buckets) {
        GalleryLibraryState.filter(items, assignments, selection, search, buckets)
    }
    val albums = remember(media, folders, assignments, buckets) { GalleryLibraryState.albums(media, folders, assignments, buckets) }
    val queue = remember(displayed) { displayed.map { it.id } }
    LaunchedEffect(queue) { val visible = queue.toHashSet(); selectedIds = ArrayList(selectedIds.filter { it in visible }) }
    val selectedSet = remember(selectedIds) { selectedIds.toHashSet() }
    val mediaGridState = rememberLazyGridState()
    val albumGridState = rememberLazyGridState()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (selecting) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton({ selectedIds = arrayListOf() }) { Icon(Icons.Rounded.Close, "Отменить выбор") }
            Text("Выбрано: ${selectedIds.size}", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            IconButton({ selectedIds = if (selectedIds.size == queue.size) arrayListOf() else ArrayList(queue) }) {
                Icon(Icons.Rounded.SelectAll, "Выбрать все")
            }
            IconButton({ onDeleteSelected?.invoke(queue.filter { it in selectedSet }) }, enabled = onDeleteSelected != null) {
                Icon(Icons.Rounded.Delete, "Удалить выбранное", tint = MaterialTheme.colorScheme.error)
            }
        }
        LazyVerticalGrid(columns = GridCells.Fixed(4), state = if (tab == 1) albumGridState else mediaGridState,
            modifier = Modifier.weight(1f).testTag("gallery-library"),
            horizontalArrangement = Arrangement.spacedBy(if (tab == 1) 0.dp else 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            item(key = "gallery-controls", span = { GridItemSpan(maxLineSpan) }) {
                if (!selecting) Column {
                    if (!detailOpen) header() else Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(::closeAlbum) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Все альбомы") }
                        Text(selectionTitle, Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (onSettings != null) Box {
                            IconButton({ detailMenu = true }) { Icon(Icons.Rounded.MoreVert, "Дополнительно") }
                            DropdownMenu(detailMenu, { detailMenu = false }) {
                                DropdownMenuItem({ Text("Настройки") }, { detailMenu = false; onSettings() }, leadingIcon = { Icon(Icons.Rounded.Settings, null) })
                            }
                        }
                    }
                    if (searchVisible != false) OutlinedTextField(search, { search = it },
                        Modifier.fillMaxWidth().padding(horizontal = 17.dp).padding(bottom = 12.dp),
                        singleLine = true, label = { Text("Поиск фото и видео") }, shape = RoundedCornerShape(12.dp),
                        leadingIcon = { Icon(Icons.Rounded.Search, null) })
                    Box(Modifier.padding(horizontal = 17.dp)) { LibraryStatus(loading, message, error,
                        tab == 0 && displayed.isEmpty(), "Нет фото и видео") }
                }
            }
            if (tab == 1) {
                item(key = "quick-title", span = { GridItemSpan(maxLineSpan) }) { GallerySectionTitle("Быстрый доступ") }
                val quick = listOf("all" to "Все медиа", "favorites" to "Избранное", "videos" to "Видео")
                itemsIndexed(quick, key = { _, pair -> "quick:${pair.first}" }, span = { _, _ -> GridItemSpan(2) }) { index, (key, title) ->
                    val count = GalleryLibraryState.filter(media, assignments, key, "", buckets).size
                    Surface(modifier = Modifier.padding(start = if (index % 2 == 0) 17.dp else 5.dp,
                        end = if (index % 2 == 0) 4.dp else 17.dp, top = 4.dp, bottom = 4.dp),
                        shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                        onClick = { openAlbum(key, title) }) {
                        Row(Modifier.padding(12.dp).heightIn(min = 42.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(when(key) { "favorites" -> Icons.Rounded.FavoriteBorder; "videos" -> Icons.Rounded.PlayArrow; else -> Icons.Rounded.Image },
                                null, Modifier.size(22.dp), MaterialTheme.colorScheme.primary)
                            Column { Text(title, fontSize = 12.sp); Text("$count файлов", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
                item(key = "albums-title", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { GallerySectionTitle(if (source == LibrarySource.APP) "Мои папки" else "Альбомы") }
                        IconButton({ creating = true }, Modifier.padding(end = 8.dp)) { Icon(Icons.Rounded.Add, "Создать папку") }
                    }
                }
                itemsIndexed(albums, key = { _, album -> album.key }, span = { _, _ -> GridItemSpan(2) }) { index, album ->
                    Column(Modifier.padding(start = if (index % 2 == 0) 17.dp else 6.dp,
                        end = if (index % 2 == 0) 6.dp else 17.dp, bottom = 18.dp)
                        .clickable { openAlbum(album.key, album.name) }) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1.25f).clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                            val cover = album.cover
                            if (cover != null) DownloadThumbnail(cover.savedUri, cover.previewUrl, cover.toUi().kind, Modifier.fillMaxSize())
                            else Icon(Icons.Rounded.PhotoLibrary, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(album.name, Modifier.padding(top = 8.dp), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${album.count} файлов · ${album.origin}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                displayed.groupBy { Instant.ofEpochMilli(it.createdAtEpochMillis).atZone(ZoneId.systemDefault()).toLocalDate() }
                    .forEach { (date, rows) ->
                        item(key = "date:$date", span = { GridItemSpan(maxLineSpan) }) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                val today = LocalDate.now()
                                Text(when(date) { today -> "Сегодня"; today.minusDays(1) -> "Вчера"; else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) },
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("${rows.size} файлов", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(rows, key = { it.id }) { row ->
                            GalleryMediaCell(row, catalog[row.id]?.isFavorite == true, { if (selecting) toggle(row.id) else onOpen(row.id, queue) },
                                { mutate { onFavorite(row.id, catalog[row.id]?.isFavorite != true) } }, { folderTarget = row.id },
                                { onShare(row.id) }, { onDelete(row.id) }, { renameTarget = row }, { onMoveToPrivate(row.id) },
                                selecting = selecting, selected = row.id in selectedSet, onSelect = { toggle(row.id) })
                        }
                    }
            }
        }
        if (tabOverride == null && !detailOpen) LibraryBottomSwitcher(AppMode.GALLERY, tab, ::selectTab)
    }
    if (creating) LibraryNameDialog("Новая папка", "", { creating = false }) { name ->
        val id = onCreateFolder(name); creating = false; openAlbum("folder:$id", name)
    }
    renameTarget?.let { row -> LibraryNameDialog("Переименовать", row.originalName, { renameTarget = null }) { name ->
        onRename(row.id, name); renameTarget = null
    } }
    folderTarget?.let { id -> AlertDialog(onDismissRequest = { folderTarget = null }, title = { Text("Выбрать папку") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            TextButton({ mutate { onSetFolder(id, null); folderTarget = null } }) { Text("Без папки") }
            folders.forEach { folder -> TextButton({ mutate { onSetFolder(id, folder.id); folderTarget = null } }) { Text(folder.name) } }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton({ folderTarget = null }) { Text("Закрыть") } }) }
}

@Composable
private fun GallerySectionTitle(text: String) { Text(text, Modifier.padding(horizontal = 17.dp, vertical = 15.dp), fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryMediaCell(item: DownloadEntity, favorite: Boolean, onOpen: () -> Unit, onFavorite: () -> Unit,
                             onFolder: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit, onRename: () -> Unit, onPrivate: () -> Unit,
                             selecting: Boolean, selected: Boolean, onSelect: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainer)
        .semantics { contentDescription = item.originalName }
        .combinedClickable(onClick = onOpen, onLongClick = onSelect, onLongClickLabel = "Выбрать файл")) {
        DownloadThumbnail(item.savedUri, item.previewUrl, item.toUi().kind, Modifier.fillMaxSize())
        if (selected) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = .25f)))
        if (selecting) Checkbox(selected, { onSelect() }, Modifier.align(Alignment.TopEnd))
        else IconButton({ menu = true }, Modifier.align(Alignment.TopEnd).size(32.dp)) {
            Icon(Icons.Rounded.MoreVert, "Действия: ${item.originalName}", tint = Color.White,
                modifier = Modifier.background(Color.Black.copy(alpha = .3f), RoundedCornerShape(8.dp)).size(22.dp))
        }
        if (item.mediaType == MediaType.VIDEO) Row(Modifier.align(Alignment.BottomEnd).padding(4.dp)
            .background(Color.Black.copy(alpha = .45f), RoundedCornerShape(4.dp)).padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.PlayArrow, "Видео", Modifier.size(13.dp), Color.White)
            val seconds = (item.durationMs ?: 0) / 1000
            Text("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", color = Color.White, fontSize = 10.sp)
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text("Открыть") }, { menu = false; onOpen() }, leadingIcon = { Icon(Icons.Rounded.OpenInFull, null) })
            DropdownMenuItem({ Text(if (favorite) "Убрать из избранного" else "В избранное") }, { menu = false; onFavorite() }, leadingIcon = { Icon(Icons.Rounded.FavoriteBorder, null) })
            DropdownMenuItem({ Text("Добавить в папку") }, { menu = false; onFolder() }, leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) })
            DropdownMenuItem({ Text("Поделиться") }, { menu = false; onShare() }, leadingIcon = { Icon(Icons.Rounded.Share, null) })
            if (item.providerId != "device") {
                DropdownMenuItem({ Text("Переименовать") }, { menu = false; onRename() }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                DropdownMenuItem({ Text("Переместить в личное") }, { menu = false; onPrivate() }, leadingIcon = { Icon(Icons.Rounded.Lock, null) })
            }
            DropdownMenuItem({ Text("Удалить", color = MaterialTheme.colorScheme.error) }, { menu = false; onDelete() },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) })
        }
    }
}

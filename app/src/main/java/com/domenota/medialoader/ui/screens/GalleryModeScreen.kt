package com.domenota.medialoader.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
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
    searchVisible: Boolean? = null, tabOverride: Int? = null, scrollToTopRequest: Int = 0, onTabChange: (Int) -> Unit = {},
    deviceAlbums: Map<String, DeviceAlbum> = emptyMap(),
    onDetailChange: (Boolean) -> Unit = {}, onSettings: (() -> Unit)? = null,
    onDeleteSelected: ((List<String>) -> Unit)? = null,
    onDeleteFolder: suspend (String) -> Unit = {},
) {
    val context = LocalContext.current
    val albumStore = remember(context) { GalleryAlbumSettingsStore(context) }
    var albumSettings by remember(source) { mutableStateOf(albumStore.load(source.name)) }
    var settingsError by remember(source) { mutableStateOf<String?>(null) }
    fun saveSettings(next: GalleryAlbumSettings) {
        if (albumStore.save(source.name, next)) {
            albumSettings = next
            settingsError = null
        } else settingsError = "Не удалось сохранить расположение альбомов"
    }
    var deletingAlbum by remember(source) { mutableStateOf<GalleryAlbum?>(null) }
    var search by rememberSaveable(source.name) { mutableStateOf("") }
    var localTab by rememberSaveable { mutableIntStateOf(0) }
    var selection by rememberSaveable(source.name) { mutableStateOf("all") }
    var selectionTitle by rememberSaveable(source.name) { mutableStateOf("") }
    var folderTarget by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<DownloadEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var detailMenu by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var selectedJumpDate by remember { mutableStateOf<LocalDate?>(null) }
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
    val albums = remember(media, folders, assignments, buckets) {
        GalleryLibraryState.albums(media, folders, assignments, buckets)
    }
    val orderedAlbums = remember(albums, albumSettings.order) { orderedGalleryAlbums(albums, albumSettings) }
    val visibleAlbums = orderedAlbums.filter { it.key !in albumSettings.hiddenAlbums }
    val otherAlbums = orderedAlbums.filter { it.key in albumSettings.hiddenAlbums }
    val visibleDisplayed = remember(displayed, selection, detailOpen, tab, albumSettings.excludedFromMedia, assignments, buckets) {
        if (tab == 0 && selection == "all") displayed.filter { row ->
            visibleInGalleryMedia(row, catalog[row.id], buckets[row.id], albumSettings.excludedFromMedia)
        } else displayed
    }
    val queue = remember(visibleDisplayed) { visibleDisplayed.map { it.id } }
    LaunchedEffect(queue) { val visible = queue.toHashSet(); selectedIds = ArrayList(selectedIds.filter { it in visible }) }
    val selectedSet = remember(selectedIds) { selectedIds.toHashSet() }
    val mediaGridState = rememberLazyGridState()
    val albumGridState = rememberLazyGridState()
    val dateSections = remember(visibleDisplayed) {
        visibleDisplayed.groupBy { Instant.ofEpochMilli(it.createdAtEpochMillis)
            .atZone(ZoneId.systemDefault()).toLocalDate() }
    }
    LaunchedEffect(tab, scrollToTopRequest) { if (tab == 0) mediaGridState.scrollToItem(0) }
    LaunchedEffect(selectedJumpDate, dateSections, tab) {
        if (tab == 0) {
            selectedJumpDate?.let { chosen ->
                galleryDateSectionIndex(dateSections.mapValues { it.value.size }, chosen)?.let {
                    mediaGridState.animateScrollToItem(it)
                }
            }
        }
    }
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
                    if (tab == 0 && !detailOpen) Row(
                        Modifier.fillMaxWidth().padding(horizontal = 17.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { showDatePicker = true }, enabled = visibleDisplayed.isNotEmpty()) {
                            Icon(Icons.Rounded.CalendarMonth, null)
                            Spacer(Modifier.width(7.dp))
                            Text("Перейти к дате")
                        }
                    }
                    Box(Modifier.padding(horizontal = 17.dp)) { LibraryStatus(loading, message, settingsError ?: error,
                        tab == 0 && visibleDisplayed.isEmpty(), "Нет фото и видео") }
                }
            }
            if (tab == 1) {
                item(key = "quick-title", span = { GridItemSpan(maxLineSpan) }) { GallerySectionTitle("Быстрый доступ") }
                val quick = listOf("all" to "Все медиа", "favorites" to "Избранное", "videos" to "Видео")
                itemsIndexed(quick, key = { _, pair -> "quick:${pair.first}" }, span = { _, _ -> GridItemSpan(2) }) { index, (key, title) ->
                    val count = if (key == "all") media.count { row ->
                        visibleInGalleryMedia(row, catalog[row.id], buckets[row.id], albumSettings.excludedFromMedia)
                    } else GalleryLibraryState.filter(media, assignments, key, "", buckets).size
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
                itemsIndexed(visibleAlbums, key = { _, album -> "album:${album.key}" },
                    span = { _, _ -> GridItemSpan(2) }) { index, album ->
                    GalleryAlbumCell(
                        album = album, index = index, hidden = false,
                        mediaExcluded = album.key in albumSettings.excludedFromMedia,
                        gridState = albumGridState,
                        onOpen = { openAlbum(album.key, album.name) },
                        onHide = { saveSettings(albumSettings.copy(hiddenAlbums = albumSettings.hiddenAlbums + album.key)) },
                        onToggleMedia = {
                            val next = if (album.key in albumSettings.excludedFromMedia)
                                albumSettings.excludedFromMedia - album.key
                            else albumSettings.excludedFromMedia + album.key
                            saveSettings(albumSettings.copy(excludedFromMedia = next))
                        },
                        onDelete = { deletingAlbum = album },
                        onMoveBefore = { key -> saveSettings(moveGalleryAlbum(albumSettings, albums, album.key, key)) },
                        onMoveUp = {
                            if (index > 0) saveSettings(moveGalleryAlbum(albumSettings, albums, album.key, visibleAlbums[index - 1].key))
                        },
                        onMoveDown = {
                            if (index < visibleAlbums.lastIndex) {
                                val keys = orderedAlbums.map { it.key }.toMutableList()
                                val a = keys.indexOf(album.key); val b = keys.indexOf(visibleAlbums[index + 1].key)
                                if (a >= 0 && b >= 0) {
                                    java.util.Collections.swap(keys, a, b)
                                    saveSettings(albumSettings.copy(order = keys))
                                }
                            }
                        },
                    )
                }
                if (otherAlbums.isNotEmpty()) {
                    item(key = "other-albums-title", span = { GridItemSpan(maxLineSpan) }) {
                        GallerySectionTitle("Другие альбомы")
                    }
                    itemsIndexed(otherAlbums, key = { _, album -> "album:${album.key}" },
                        span = { _, _ -> GridItemSpan(2) }) { index, album ->
                        GalleryAlbumCell(
                            album = album, index = index, hidden = true,
                            mediaExcluded = album.key in albumSettings.excludedFromMedia,
                            gridState = albumGridState,
                            onOpen = { openAlbum(album.key, album.name) },
                            onHide = { saveSettings(albumSettings.copy(hiddenAlbums = albumSettings.hiddenAlbums - album.key)) },
                            onToggleMedia = {
                                val next = if (album.key in albumSettings.excludedFromMedia)
                                    albumSettings.excludedFromMedia - album.key
                                else albumSettings.excludedFromMedia + album.key
                                saveSettings(albumSettings.copy(excludedFromMedia = next))
                            },
                            onDelete = { deletingAlbum = album },
                            onMoveBefore = { key -> saveSettings(moveGalleryAlbum(albumSettings, albums, album.key, key)) },
                            onMoveUp = {
                                if (index > 0) saveSettings(moveGalleryAlbum(albumSettings, albums, album.key, otherAlbums[index - 1].key))
                            },
                            onMoveDown = {
                                if (index < otherAlbums.lastIndex) {
                                    val keys = orderedAlbums.map { it.key }.toMutableList()
                                    val a = keys.indexOf(album.key); val b = keys.indexOf(otherAlbums[index + 1].key)
                                    if (a >= 0 && b >= 0) {
                                        java.util.Collections.swap(keys, a, b)
                                        saveSettings(albumSettings.copy(order = keys))
                                    }
                                }
                            },
                        )
                    }
                }
            } else {
                dateSections.forEach { (date, rows) ->
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
    deletingAlbum?.let { album ->
        val appFolder = album.key.startsWith("folder:")
        val albumRows = GalleryLibraryState.filter(media, assignments, album.key, "", buckets)
        AlertDialog(
            onDismissRequest = { deletingAlbum = null },
            title = { Text(if (appFolder) "Удалить папку?" else "Удалить альбом и файлы?") },
            text = {
                Text(if (appFolder)
                    "Папка «${album.name}» будет удалена из MediaLoader. Её ${album.count} файлов сохранятся в «Медиа» без этой папки."
                else "В альбоме «${album.name}» ${albumRows.size} файлов. Они будут удалены с устройства после системного подтверждения. Это не простое скрытие.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deletingAlbum = null
                        if (appFolder) {
                            mutate {
                                onDeleteFolder(album.key.removePrefix("folder:"))
                                saveSettings(removeGalleryAlbumSettings(albumSettings, album.key))
                            }
                        } else onDeleteSelected?.invoke(albumRows.map { it.id })
                    },
                    enabled = appFolder || (onDeleteSelected != null && albumRows.isNotEmpty())
                ) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ deletingAlbum = null }) { Text("Отмена") } },
        )
    }
    if (showDatePicker) {
        GalleryDatePicker(
            onDismiss = { showDatePicker = false },
            onPick = { chosen ->
                showDatePicker = false
                selectedJumpDate = chosen
            },
        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryAlbumCell(
    album: GalleryAlbum, index: Int, hidden: Boolean, mediaExcluded: Boolean,
    gridState: LazyGridState, onOpen: () -> Unit, onHide: () -> Unit,
    onToggleMedia: () -> Unit, onDelete: () -> Unit,
    onMoveBefore: (String) -> Unit, onMoveUp: () -> Unit, onMoveDown: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragShift by remember(album.key) { mutableStateOf(Offset.Zero) }
    Column(
        Modifier
            .offset { IntOffset(dragShift.x.roundToInt(), dragShift.y.roundToInt()) }
            .zIndex(if (dragging) 2f else 0f)
            .padding(start = if (index % 2 == 0) 17.dp else 6.dp,
                end = if (index % 2 == 0) 6.dp else 17.dp, bottom = 18.dp)
            .pointerInput(album.key) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragging = true; dragShift = Offset.Zero },
                    onDrag = { change, distance ->
                        change.consume()
                        dragShift += distance
                    },
                    onDragEnd = {
                        val source = gridState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == "album:${album.key}" }
                        if (source != null) {
                            val x = source.offset.x + source.size.width / 2f + dragShift.x
                            val y = source.offset.y + source.size.height / 2f + dragShift.y
                            val target = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                info.key != source.key && info.key.toString().startsWith("album:") &&
                                    x >= info.offset.x && x < info.offset.x + info.size.width &&
                                    y >= info.offset.y && y < info.offset.y + info.size.height
                            }
                            target?.let { onMoveBefore(it.key.toString().removePrefix("album:")) }
                        }
                        dragging = false
                        dragShift = Offset.Zero
                    },
                    onDragCancel = { dragging = false; dragShift = Offset.Zero },
                )
            }
            .clickable(onClick = onOpen)
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.25f).clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            val cover = album.cover
            if (cover != null) DownloadThumbnail(
                cover.savedUri, cover.previewUrl, cover.toUi().kind, Modifier.fillMaxSize()
            )
            else Icon(Icons.Rounded.PhotoLibrary, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton({ menu = true }) {
                    Icon(Icons.Rounded.MoreVert, "Управление альбомом ${album.name}",
                        tint = Color.White,
                        modifier = Modifier.background(
                            Color.Black.copy(alpha = .45f), RoundedCornerShape(8.dp)))
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(if (hidden) "Вернуть в альбомы" else "В другие альбомы") },
                        { menu = false; onHide() },
                        leadingIcon = { Icon(Icons.Rounded.VisibilityOff, null) })
                    DropdownMenuItem({
                        Text(if (mediaExcluded) "Показывать файлы в «Медиа»" else "Скрыть файлы из «Медиа»")
                    }, { menu = false; onToggleMedia() },
                        leadingIcon = { Icon(Icons.Rounded.RemoveRedEye, null) })
                    DropdownMenuItem({ Text("Переместить выше") }, { menu = false; onMoveUp() },
                        leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null) })
                    DropdownMenuItem({ Text("Переместить ниже") }, { menu = false; onMoveDown() },
                        leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) })
                    DropdownMenuItem({ Text("Удалить альбом", color = MaterialTheme.colorScheme.error) },
                        { menu = false; onDelete() },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) })
                }
            }
        }
        Text(album.name, Modifier.padding(top = 8.dp), fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${album.count} файлов · ${album.origin}", fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryDatePicker(onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { millis ->
                    // Material DatePicker encodes dates in UTC, not the phone's current timezone.
                    onPick(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                }
            }, enabled = state.selectedDateMillis != null) { Text("Перейти") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    ) {
        DatePicker(state = state)
    }
}

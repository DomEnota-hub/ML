package com.domenota.medialoader.ui.screens

import android.widget.Toast
import androidx.compose.foundation.border

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
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.delay
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
import com.domenota.medialoader.ui.screens.music.GalleryFastScroll
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
    onDetailChange: (Boolean) -> Unit = {}, onTrash: (() -> Unit)? = null,
    onDeleteSelected: ((List<String>) -> Unit)? = null,
    onDeleteFolder: suspend (String) -> Unit = {},
    onDeviceAlbumsChanged: () -> Unit = {},
    datePickerRequest: Int = 0,
    albumRequest: GalleryAlbumRequest? = null,
    onAlbumRequestHandled: () -> Unit = {},
    onOpenFromAlbum: ((String, List<String>, String?) -> Unit)? = null,
) {
    val context = LocalContext.current
    val albumStore = remember(context) { GalleryAlbumSettingsStore(context) }
    val albumFileSortStore = remember(context) { GalleryAlbumFileSortStore(context) }
    var albumSettings by remember(source) { mutableStateOf(albumStore.load(source.name)) }
    var settingsError by remember(source) { mutableStateOf<String?>(null) }
    fun saveSettings(next: GalleryAlbumSettings) {
        if (albumStore.save(source.name, next)) {
            albumSettings = next
            settingsError = null
        } else settingsError = "Не удалось сохранить расположение альбомов"
    }
    var deletingAlbum by remember(source) { mutableStateOf<GalleryAlbum?>(null) }
    var otherAlbumsExpanded by rememberSaveable(source.name) { mutableStateOf(false) }
    var search by rememberSaveable(source.name) { mutableStateOf("") }
    var localTab by rememberSaveable { mutableIntStateOf(0) }
    var selection by rememberSaveable(source.name) { mutableStateOf("all") }
    var selectionTitle by rememberSaveable(source.name) { mutableStateOf("") }
    var albumFileSort by remember(source.name, selection) {
        mutableStateOf(albumFileSortStore.read(source.name, selection))
    }
    var albumSortMenuExpanded by remember { mutableStateOf(false) }
    var folderTarget by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<DownloadEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var handledDateRequest by rememberSaveable { mutableStateOf(datePickerRequest) }
    LaunchedEffect(datePickerRequest) {
        if (handledDateRequest != datePickerRequest) { handledDateRequest = datePickerRequest; showDatePicker = true }
    }
    var selectedJumpDate by remember { mutableStateOf<LocalDate?>(null) }
    val detailOpen = selectionTitle.isNotEmpty()
    LaunchedEffect(detailOpen) { onDetailChange(detailOpen) }
    DisposableEffect(Unit) { onDispose { onDetailChange(false) } }
    val tab = tabOverride ?: localTab
    fun selectTab(value: Int) { localTab = value; onTabChange(value) }
    fun openAlbum(key: String, title: String) { selection = key; selectionTitle = title; selectTab(0) }
    fun closeAlbum() { selection = "all"; selectionTitle = ""; selectTab(1) }
    // An update can restore the previous Activity's saved virtual album route.
    LaunchedEffect(selection) { if (selection.startsWith("smart:")) closeAlbum() }
    fun openMedia(id: String, queue: List<String>) {
        val origin = selection.takeIf { detailOpen && (it.startsWith("folder:") || it.startsWith("device:")) }
        if (onOpenFromAlbum != null) onOpenFromAlbum(id,queue,origin) else onOpen(id,queue)
    }
    var jumpToMedia by remember { mutableStateOf<String?>(null) }
    var highlightedMedia by remember { mutableStateOf<String?>(null) }
    BackHandler(detailOpen || tab != 0) { if (detailOpen) closeAlbum() else selectTab(0) }
    LaunchedEffect(searchVisible) { if (searchVisible == false) search = "" }
    // Multi-select can include the entire phone library. Keep large IDs in
    // transient screen state instead of serializing them into Android Bundles.
    var selectedIds by remember(source.name, selection, search, tab) { mutableStateOf(arrayListOf<String>()) }
    var moveSnapshot by remember { mutableStateOf<List<DownloadEntity>?>(null) }
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
    LaunchedEffect(albumRequest, albums, loading, media) {
        val request = albumRequest ?: return@LaunchedEffect
        val found = albums.firstOrNull { it.key == request.album.key }
        if (found != null && GalleryLibraryState.filter(media, assignments, found.key, "", buckets).any { it.id == request.itemId }) {
            search = ""; selection = found.key; selectionTitle = found.name; if (tab != 0) selectTab(0); jumpToMedia = request.itemId
            onAlbumRequestHandled()
        } else if (!loading) { Toast.makeText(context, "Альбом недоступен", Toast.LENGTH_SHORT).show(); onAlbumRequestHandled() }
    }
    val orderedAlbums = remember(albums, albumSettings.order) { orderedGalleryAlbums(albums, albumSettings) }
    val visibleAlbums = orderedAlbums.filter { it.key !in albumSettings.hiddenAlbums }
    val standardQuickKeys = remember(visibleAlbums) {
        galleryStandardAlbums(visibleAlbums).map { it.key }.toSet()
    }
    // The five fixed shortcuts come first; device Camera/Screenshots never duplicate pinned cards.
    val quickAlbums = visibleAlbums.filter {
        it.key in albumSettings.quickAlbums && it.key !in standardQuickKeys
    }
    val regularAlbums = visibleAlbums.filter {
        it.key !in albumSettings.quickAlbums && it.key !in standardQuickKeys
    }
    val otherAlbums = orderedAlbums.filter { it.key in albumSettings.hiddenAlbums }
    val shortcutAllMedia = remember(media, catalog, buckets, albumSettings.excludedFromMedia) {
        media.filter { row ->
            visibleInGalleryMedia(row, catalog[row.id], buckets[row.id],
                albumSettings.excludedFromMedia)
        }
    }
    val shortcutVideos = remember(media, assignments, buckets) {
        GalleryLibraryState.filter(media, assignments, "videos", "", buckets)
    }
    val shortcutFavorites = remember(media, assignments, buckets) {
        GalleryLibraryState.filter(media, assignments, "favorites", "", buckets)
    }
    val defaultQuickItems = remember(visibleAlbums, shortcutAllMedia, shortcutVideos, shortcutFavorites) {
        galleryDefaultQuickItems(visibleAlbums, shortcutAllMedia, shortcutVideos, shortcutFavorites)
    }
    val visibleDisplayed = remember(displayed, selection, detailOpen, tab, albumSettings.excludedFromMedia, assignments, buckets) {
        if (tab == 0 && selection == "all") displayed.filter { row ->
            visibleInGalleryMedia(row, catalog[row.id], buckets[row.id], albumSettings.excludedFromMedia)
        } else displayed
    }
    val orderedDisplayed = remember(visibleDisplayed, detailOpen, albumFileSort) {
        if (detailOpen) GalleryAlbumFileOrder.sorted(visibleDisplayed, albumFileSort) else visibleDisplayed
    }
    val queue = remember(orderedDisplayed) { orderedDisplayed.map { it.id } }
    LaunchedEffect(queue) { val visible = queue.toHashSet(); selectedIds = ArrayList(selectedIds.filter { it in visible }) }
    val selectedSet = remember(selectedIds) { selectedIds.toHashSet() }
    val queueSet = remember(queue) { queue.toHashSet() }
    val mediaGridState = rememberLazyGridState()
    val albumGridState = rememberLazyGridState()
    val density = LocalDensity.current
    var galleryOverlayHeightPx by remember { mutableIntStateOf(0) }
    val edgeHeightPx = with(density) { 84.dp.toPx() }
    var draggingAlbumKey by remember { mutableStateOf<String?>(null) }
    var draggedPointer by remember { mutableStateOf(Offset.Zero) }
    var albumViewport by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(draggingAlbumKey, tab) {
        if (tab == 1 && draggingAlbumKey != null) {
            // The gesture stays on the grid, not on its recyclable items.
            // This continues scrolling even if the source card leaves the viewport.
            while (draggingAlbumKey != null) {
                val delta = galleryAlbumEdgeScrollDelta(
                    draggedPointer.y, albumViewport.height.toFloat(), edgeHeightPx)
                if (delta != 0f) albumGridState.scrollBy(delta)
                delay(16L)
            }
        }
    }
    // A phone gallery can hold 10k+ files: do not parse every timestamp on each selection recomposition.
    val currentCalendarDay = LocalDate.now()
    val selectableDateRange = remember(visibleDisplayed, currentCalendarDay) {
        galleryDateRange(visibleDisplayed.map { it.createdAtEpochMillis }, currentCalendarDay)
    }
    val dateSections = remember(orderedDisplayed) {
        orderedDisplayed.groupBy { Instant.ofEpochMilli(it.createdAtEpochMillis)
            .atZone(ZoneId.systemDefault()).toLocalDate() }
    }
    // One label per actual grid item, including controls and full-width date headers.
    // When sorting by name/size, the label is the date of the actual target file.
    val galleryScrollLabels = remember(orderedDisplayed, dateSections, detailOpen, albumFileSort) {
        galleryFastScrollLabels(orderedDisplayed, !detailOpen || GalleryAlbumFileOrder.groupedByDate(albumFileSort))
    }
    LaunchedEffect(jumpToMedia, orderedDisplayed, detailOpen, tab, albumFileSort) {
        val id = jumpToMedia ?: return@LaunchedEffect
        if (!detailOpen || tab != 0) return@LaunchedEffect
        val grouped = GalleryAlbumFileOrder.groupedByDate(albumFileSort)
        val groups = if (grouped) dateSections.values.map { rows -> rows.map { it.id } } else listOf(orderedDisplayed.map { it.id })
        val index = GalleryLibraryState.gridIndex(groups,id,grouped) ?: return@LaunchedEffect
        mediaGridState.scrollToItem(index)
        jumpToMedia = null; highlightedMedia = id
    }
    LaunchedEffect(highlightedMedia) { if (highlightedMedia != null) { delay(2600); highlightedMedia = null } }
    // Keep the long-press drag detector on the grid, not a recycled cell:
    // otherwise automatic scrolling would cancel the gesture.
    var dragSelectionAnchor by remember { mutableStateOf<String?>(null) }
    var dragSelectionPointer by remember { mutableStateOf(Offset.Zero) }
    var dragSelectionBase by remember { mutableStateOf(emptySet<String>()) }
    var dragSelectionEnd by remember { mutableStateOf<String?>(null) }
    fun galleryItemAt(point: Offset): String? {
        val visible = mediaGridState.layoutInfo.visibleItemsInfo.filter { it.key.toString() in queueSet }
        val hit = visible.firstOrNull { cell ->
            point.x >= cell.offset.x && point.x < cell.offset.x + cell.size.width &&
                point.y >= cell.offset.y && point.y < cell.offset.y + cell.size.height
        }
        return hit?.key?.toString() ?: when {
            point.y <= 0f -> visible.firstOrNull()?.key?.toString()
            point.y >= mediaGridState.layoutInfo.viewportEndOffset ->
                visible.lastOrNull()?.key?.toString()
            else -> null
        }
    }
    fun updateGalleryDrag(point: Offset) {
        val anchor = dragSelectionAnchor ?: return
        val current = galleryItemAt(point) ?: return
        if (current == dragSelectionEnd) return
        dragSelectionEnd = current
        selectedIds = ArrayList(galleryRangeSelection(dragSelectionBase, queue, anchor, current))
    }
    LaunchedEffect(dragSelectionAnchor, tab) {
        if (tab != 0 || dragSelectionAnchor == null) return@LaunchedEffect
        while (dragSelectionAnchor != null) {
            val delta = galleryAlbumEdgeScrollDelta(
                dragSelectionPointer.y,
                mediaGridState.layoutInfo.viewportSize.height.toFloat(),
                edgeHeightPx,
            )
            if (delta != 0f) {
                mediaGridState.scrollBy(delta)
                updateGalleryDrag(dragSelectionPointer)
            }
            delay(16L)
        }
    }
    LaunchedEffect(queue) { dragSelectionAnchor = null }
    // Only an explicit new "top" request may reset scrolling. Recomposition
    // after closing a viewer must preserve LazyGridState and its exact offset.
    val topRequestTracker = remember { GalleryScrollRequestTracker(scrollToTopRequest) }
    LaunchedEffect(scrollToTopRequest, tab) {
        if (topRequestTracker.shouldScrollToTop(scrollToTopRequest, tab == 0)) {
            mediaGridState.scrollToItem(0)
        }
    }
    LaunchedEffect(selectedJumpDate, dateSections, tab, albumFileSort, detailOpen) {
        if (tab == 0 && (!detailOpen || GalleryAlbumFileOrder.groupedByDate(albumFileSort))) {
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
            IconButton({ moveSnapshot = orderedDisplayed.filter { it.id in selectedSet } }) {
                Icon(Icons.Rounded.DriveFileMove, "Переместить выбранные в альбом")
            }
            IconButton({ onDeleteSelected?.invoke(queue.filter { it in selectedSet }) }, enabled = onDeleteSelected != null) {
                Icon(Icons.Rounded.Delete, "Удалить выбранное", tint = MaterialTheme.colorScheme.error)
            }
        }
        Box(Modifier.weight(1f)) {
        LazyVerticalGrid(columns = GridCells.Fixed(if (tab == 1) 6 else 4), state = if (tab == 1) albumGridState else mediaGridState,
            modifier = Modifier.fillMaxSize().testTag("gallery-library")
                .onSizeChanged { albumViewport = it }
                .pointerInput(tab, queue) {
                    if (tab != 0) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = { point ->
                            val first = galleryItemAt(point)
                            if (first != null) {
                                dragSelectionAnchor = first
                                dragSelectionPointer = point
                                dragSelectionBase = selectedIds.toSet()
                                dragSelectionEnd = null
                                updateGalleryDrag(point)
                            }
                        },
                        onDrag = { change, distance ->
                            if (dragSelectionAnchor != null) {
                                change.consume()
                                dragSelectionPointer += distance
                                updateGalleryDrag(dragSelectionPointer)
                            }
                        },
                        onDragEnd = {
                            dragSelectionAnchor = null
                            dragSelectionEnd = null
                        },
                        onDragCancel = {
                            dragSelectionAnchor = null
                            dragSelectionEnd = null
                        },
                    )
                }
                .pointerInput(tab, albums, albumSettings.hiddenAlbums, otherAlbumsExpanded) {
                    if (tab != 1) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = { point ->
                            val hit = albumGridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                item.key.toString().startsWith("album:") &&
                                    point.x >= item.offset.x && point.x < item.offset.x + item.size.width &&
                                    point.y >= item.offset.y && point.y < item.offset.y + item.size.height
                            }
                            draggingAlbumKey = hit?.key?.toString()?.removePrefix("album:")
                            draggedPointer = point
                        },
                        onDrag = { change, distance ->
                            if (draggingAlbumKey != null) {
                                change.consume()
                                draggedPointer += distance
                            }
                        },
                        onDragEnd = {
                            val from = draggingAlbumKey
                            if (from != null) {
                                val visible = albumGridState.layoutInfo.visibleItemsInfo
                                val hit = visible.firstOrNull { item -> draggedPointer.x >= item.offset.x && draggedPointer.x < item.offset.x + item.size.width &&
                                    draggedPointer.y >= item.offset.y && draggedPointer.y < item.offset.y + item.size.height }
                                val hitKey = hit?.key?.toString()
                                val target = hitKey?.takeIf { it.startsWith("album:") }?.removePrefix("album:")
                                val boundary = visible.firstOrNull { it.key == "albums-title" }?.offset?.y
                                val toQuick = when {
                                    boundary != null -> draggedPointer.y < boundary
                                    hitKey == "quick-title" || hitKey?.startsWith("quick:") == true -> true
                                    target != null -> target in albumSettings.quickAlbums
                                    hitKey == "albums-title" || hitKey == "other-albums-title" -> false
                                    else -> null
                                }
                                if (toQuick != null && draggedPointer.x in 0f..albumViewport.width.toFloat() && draggedPointer.y in 0f..albumViewport.height.toFloat())
                                    saveSettings(dropGalleryAlbum(albumSettings, albums, from, target, toQuick))
                            }
                            draggingAlbumKey = null
                            draggedPointer = Offset.Zero
                        },
                        onDragCancel = {
                            draggingAlbumKey = null
                            draggedPointer = Offset.Zero
                        },
                    )
                },
            horizontalArrangement = Arrangement.spacedBy(if (tab == 1) 0.dp else 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            // Media flows beneath the floating switcher, rather than ending in
            // a fixed, opaque 106dp blank strip.
            // Leave enough scroll tail to expose the final row above the overlay.
            // The tail lives *inside* the scrollable grid, not in Scaffold.
            contentPadding = PaddingValues(end = if (tab == 0 && orderedDisplayed.size > 4) 36.dp else 0.dp,
                bottom = with(density) { galleryOverlayHeightPx.toDp() } + 12.dp)) {
            item(key = "gallery-controls", span = { GridItemSpan(maxLineSpan) }) {
                if (!selecting) Column {
                    if (!detailOpen) header() else Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(::closeAlbum) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Все альбомы") }
                        Text(selectionTitle, Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box {
                            IconButton(
                                onClick = { albumSortMenuExpanded = true },
                                modifier = Modifier.testTag("album-file-sort"),
                            ) {
                                Icon(Icons.Rounded.Sort, "Сортировка файлов альбома", tint = Color.White)
                            }
                            DropdownMenu(
                                expanded = albumSortMenuExpanded,
                                onDismissRequest = { albumSortMenuExpanded = false },
                            ) {
                                GalleryAlbumFileSort.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label, color = if (albumFileSort == option) com.domenota.medialoader.ui.theme.musicPlaybackAccent else MaterialTheme.colorScheme.onSurface) },
                                        onClick = {
                                            albumSortMenuExpanded = false
                                            albumFileSortStore.save(source.name, selection, option)
                                            albumFileSort = option
                                            selectedJumpDate = null
                                        },
                                    )
                                }
                            }
                        }
                        if (onTrash != null) IconButton(onTrash) {
                            Icon(Icons.Rounded.DeleteOutline, "Корзина")
                        }
                    }
                    if (searchVisible != false) OutlinedTextField(search, { search = it },
                        Modifier.fillMaxWidth().padding(horizontal = 17.dp).padding(bottom = 12.dp),
                        singleLine = true, label = { Text("Поиск фото и видео") }, shape = RoundedCornerShape(12.dp),
                        leadingIcon = { Icon(Icons.Rounded.Search, null) })
                    Box(Modifier.padding(horizontal = 17.dp)) { LibraryStatus(loading, message, settingsError ?: error,
                        tab == 0 && visibleDisplayed.isEmpty(), "Нет фото и видео") }
                }
            }
            if (tab == 1) {
                item(key = "quick-title", span = { GridItemSpan(maxLineSpan) }) { GallerySectionTitle("Быстрый доступ") }
                itemsIndexed(defaultQuickItems, key = { _, shortcut -> "quick:${shortcut.key}" },
                    span = { _, _ -> GridItemSpan(3) }) { index, shortcut ->
                    GalleryQuickAccessCell(
                        shortcut = shortcut,
                        index = index,
                        onOpen = { openAlbum(shortcut.key, shortcut.title) },
                    )
                }
                itemsIndexed(quickAlbums, key = { _, album -> "album:${album.key}" },
                    span = { _, _ -> GridItemSpan(3) }) { index, album ->
                    GalleryQuickAccessCell(
                        shortcut = GalleryQuickItem(album.key, album.name, GalleryQuickKind.ALBUM,
                            album.count, album.cover),
                        index = index + defaultQuickItems.size,
                        dragged = draggingAlbumKey == album.key,
                        mediaExcluded = album.key in albumSettings.excludedFromMedia,
                        onOpen = { openAlbum(album.key, album.name) },
                        onUnpin = { saveSettings(albumSettings.copy(
                            quickAlbums = albumSettings.quickAlbums - album.key)) },
                        onToggleMedia = {
                            val next = if (album.key in albumSettings.excludedFromMedia)
                                albumSettings.excludedFromMedia - album.key
                            else albumSettings.excludedFromMedia + album.key
                            saveSettings(albumSettings.copy(excludedFromMedia = next))
                        },
                        onDelete = { deletingAlbum = album },
                    )
                }
                item(key = "albums-title", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { GallerySectionTitle(if (source == LibrarySource.APP) "Мои папки" else "Альбомы") }
                        IconButton({ creating = true }, Modifier.padding(end = 8.dp)) { Icon(Icons.Rounded.Add, "Создать папку") }
                    }
                }
                itemsIndexed(regularAlbums, key = { _, album -> "album:${album.key}" },
                    span = { _, _ -> GridItemSpan(2) }) { index, album ->
                    GalleryAlbumCell(
                        album = album, index = index, hidden = false,
                        mediaExcluded = album.key in albumSettings.excludedFromMedia,
                        dragged = draggingAlbumKey == album.key,
                        columns = 3,
                        onOpen = { openAlbum(album.key, album.name) },
                        onHide = {
                            saveSettings(albumSettings.copy(hiddenAlbums = albumSettings.hiddenAlbums + album.key))
                        },
                        onToggleMedia = {
                            val next = if (album.key in albumSettings.excludedFromMedia)
                                albumSettings.excludedFromMedia - album.key
                            else albumSettings.excludedFromMedia + album.key
                            saveSettings(albumSettings.copy(excludedFromMedia = next))
                        },
                        onDelete = { deletingAlbum = album },
                    )
                }
                if (otherAlbums.isNotEmpty()) {
                    item(key = "other-albums-title", span = { GridItemSpan(maxLineSpan) }) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 10.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            onClick = { otherAlbumsExpanded = !otherAlbumsExpanded },
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(Icons.Rounded.FolderOpen, null,
                                    tint = MaterialTheme.colorScheme.onSurface)
                                Text("Другие альбомы", modifier = Modifier.weight(1f),
                                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text("${otherAlbums.size}",
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Icon(if (otherAlbumsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    if (otherAlbumsExpanded) "Свернуть" else "Развернуть")
                            }
                        }
                    }
                    if (otherAlbumsExpanded) {
                        itemsIndexed(otherAlbums, key = { _, album -> "album:${album.key}" },
                            span = { _, _ -> GridItemSpan(2) }) { index, album ->
                            GalleryAlbumCell(
                                album = album, index = index, hidden = true,
                                mediaExcluded = album.key in albumSettings.excludedFromMedia,
                                dragged = draggingAlbumKey == album.key,
                                columns = 3,
                                onOpen = { openAlbum(album.key, album.name) },
                                onHide = {
                                    saveSettings(albumSettings.copy(
                                        hiddenAlbums = albumSettings.hiddenAlbums - album.key))
                                },
                                onToggleMedia = {
                                    val next = if (album.key in albumSettings.excludedFromMedia)
                                        albumSettings.excludedFromMedia - album.key
                                    else albumSettings.excludedFromMedia + album.key
                                    saveSettings(albumSettings.copy(excludedFromMedia = next))
                                },
                                onDelete = { deletingAlbum = album },
                            )
                        }
                    }
                }
            } else {
                if (detailOpen && !GalleryAlbumFileOrder.groupedByDate(albumFileSort)) {
                    items(orderedDisplayed, key = { it.id }) { row ->
                        GalleryMediaCell(row, catalog[row.id]?.isFavorite == true,
                            { if (selecting) toggle(row.id) else openMedia(row.id, queue) },
                            { mutate { onFavorite(row.id, catalog[row.id]?.isFavorite != true) } },
                            { folderTarget = row.id }, { onShare(row.id) }, { onDelete(row.id) },
                            { renameTarget = row }, { onMoveToPrivate(row.id) },
                            selecting = selecting, selected = row.id in selectedSet,
                            onSelect = { toggle(row.id) },
                            onLongSelect = { selectedIds = ArrayList(selectedIds.toSet() + row.id) }, highlighted = row.id == highlightedMedia)
                    }
                } else dateSections.forEach { (date, rows) ->
                        item(key = "date:$date", span = { GridItemSpan(maxLineSpan) }) {
                            val dayIds = remember(rows) { rows.map { it.id } }
                            val selectedInDay = dayIds.count { it in selectedSet }
                            Row(
                                Modifier.fillMaxWidth().padding(start = 17.dp, end = 9.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val today = LocalDate.now()
                                Column(Modifier.weight(1f)) {
                                    Text(when(date) { today -> "Сегодня"; today.minusDays(1) -> "Вчера"; else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) },
                                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text(if (selectedInDay > 0) "${selectedInDay}/${rows.size} выбрано" else "${rows.size} файлов",
                                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(
                                    onClick = {
                                        selectedIds = ArrayList(toggleGalleryDaySelection(selectedSet, queue, dayIds))
                                    },
                                    modifier = Modifier.testTag("select-day:$date").semantics {
                                        contentDescription = "Выбрать все файлы за $date"
                                    },
                                ) {
                                    Icon(when {
                                        selectedInDay == dayIds.size -> Icons.Rounded.CheckCircle
                                        selectedInDay > 0 -> Icons.Rounded.RemoveCircleOutline
                                        else -> Icons.Rounded.RadioButtonUnchecked
                                    }, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (selectedInDay == dayIds.size) "Снять выбор" else "Выбрать все", fontSize = 12.sp)
                                }
                            }
                        }
                        items(rows, key = { it.id }) { row ->
                            GalleryMediaCell(row, catalog[row.id]?.isFavorite == true, { if (selecting) toggle(row.id) else openMedia(row.id, queue) },
                                { mutate { onFavorite(row.id, catalog[row.id]?.isFavorite != true) } }, { folderTarget = row.id },
                                { onShare(row.id) }, { onDelete(row.id) }, { renameTarget = row }, { onMoveToPrivate(row.id) },
                                selecting = selecting, selected = row.id in selectedSet,
                                onSelect = { toggle(row.id) },
                                onLongSelect = { selectedIds = ArrayList(selectedIds.toSet() + row.id) }, highlighted = row.id == highlightedMedia)
                        }
                    }
            }
        }
        if (tab == 0 && dragSelectionAnchor == null) {
            GalleryFastScroll(mediaGridState, galleryScrollLabels,
                Modifier.align(Alignment.CenterEnd)
                    .padding(bottom = with(density) { galleryOverlayHeightPx.toDp() })
                    .zIndex(4f))
        }
        // Floating drag preview remains present even when its original row scrolls out of view.
        draggingAlbumKey?.let { key ->
            orderedAlbums.firstOrNull { it.key == key }?.let { album ->
                Surface(
                    modifier = Modifier.offset {
                        IntOffset(
                            (draggedPointer.x - with(density) { 67.dp.toPx() }).roundToInt()
                                .coerceAtLeast(0),
                            (draggedPointer.y - with(density) { 48.dp.toPx() }).roundToInt()
                                .coerceAtLeast(0)
                        )
                    }.width(134.dp).zIndex(5f).alpha(0.93f),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 9.dp,
                ) {
                    Column(Modifier.padding(9.dp)) {
                        album.cover?.let { cover ->
                            DownloadThumbnail(cover.savedUri, cover.previewUrl,
                                cover.toUi().kind,
                                Modifier.fillMaxWidth().height(65.dp).clip(RoundedCornerShape(9.dp)), progressive = true)
                        }
                        Text(album.name, fontSize = 12.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
            .onSizeChanged { galleryOverlayHeightPx = it.height }.zIndex(6f)) {
            if (!detailOpen) LibraryBottomSwitcher(AppMode.GALLERY, tab, ::selectTab)
        }
        }
    }
    moveSnapshot?.let { snapshot ->
        GalleryBulkAlbumMoveSheet(
            selected = snapshot,
            albums = albums,
            deviceAlbums = buckets,
            source = source,
            onCreateFolder = onCreateFolder,
            onSetFolder = onSetFolder,
            onDismiss = { moveSnapshot = null },
            onDeviceMoveCompleted = onDeviceAlbumsChanged,
            onFinished = { done, message ->
                selectedIds = ArrayList(GalleryAlbumMovePolicy.remainingSelection(selectedIds, done))
                moveSnapshot = null
                error = if (done.size == snapshot.size) null else message
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            },
        )
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
                else "В альбоме «${album.name}» ${albumRows.size} файлов. Они будут перемещены в корзину после системного подтверждения и смогут быть восстановлены.")
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
    if (showDatePicker && selectableDateRange != null) {
        GalleryDatePicker(
            range = selectableDateRange,
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
    dragged: Boolean, onOpen: () -> Unit, onHide: () -> Unit,
    onToggleMedia: () -> Unit, onDelete: () -> Unit, columns: Int = 2, pinned: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .alpha(if (dragged) 0.30f else 1f)
            .padding(start = if (index % columns == 0) 17.dp else 5.dp,
                end = if (index % columns == columns - 1) 17.dp else 5.dp, bottom = 18.dp)
            .clickable(onClick = onOpen)
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.25f).clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            val cover = album.cover
            if (cover != null) DownloadThumbnail(
                cover.savedUri, cover.previewUrl, cover.toUi().kind, Modifier.fillMaxSize(), progressive = true
            )
            else Icon(Icons.Rounded.PhotoLibrary, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (mediaExcluded) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(7.dp)
                        .background(Color.Black.copy(alpha = .65f), RoundedCornerShape(8.dp))
                        .padding(5.dp)
                ) {
                    Icon(Icons.Rounded.VisibilityOff, "Файлы скрыты из «Медиа»",
                        Modifier.size(19.dp), tint = Color.White)
                }
            }
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton({ menu = true }) {
                    Icon(Icons.Rounded.MoreVert, "Управление альбомом ${album.name}",
                        tint = Color.White,
                        modifier = Modifier.background(
                            Color.Black.copy(alpha = .45f), RoundedCornerShape(8.dp)))
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(if (pinned) "Убрать из быстрого доступа" else if (hidden) "Вернуть в альбомы" else "В другие альбомы") },
                        { menu = false; onHide() },
                        leadingIcon = { Icon(Icons.Rounded.VisibilityOff, null) })
                    DropdownMenuItem({
                        Text(if (mediaExcluded) "Показывать файлы в «Медиа»" else "Скрыть файлы из «Медиа»")
                    }, { menu = false; onToggleMedia() },
                        leadingIcon = { Icon(Icons.Rounded.RemoveRedEye, null) })
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
                             selecting: Boolean, selected: Boolean, onSelect: () -> Unit,
                             onLongSelect: () -> Unit, highlighted: Boolean = false) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainer)
        .semantics { contentDescription = item.originalName }
        .combinedClickable(onClick = onOpen, onLongClick = onLongSelect, onLongClickLabel = "Выбрать файл")) {
        DownloadThumbnail(item.savedUri, item.previewUrl, item.toUi().kind, Modifier.fillMaxSize(), progressive = true)
        if (highlighted) Box(Modifier.matchParentSize().border(3.dp, if (com.domenota.medialoader.ui.theme.isDarkPalette) com.domenota.medialoader.ui.theme.AppGold else com.domenota.medialoader.ui.theme.LightAppAccent))
        if (selecting) Box(Modifier.align(Alignment.TopEnd).size(40.dp)) {
            IconButton(onClick = onSelect, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(23.dp)
                    .background(if (selected) Color(0xFF4285F4) else Color.Transparent, androidx.compose.foundation.shape.CircleShape)
                    .then(Modifier.border(2.dp, Color.White, androidx.compose.foundation.shape.CircleShape)), contentAlignment = Alignment.Center) {
                    if (selected) Icon(Icons.Rounded.Check, "Снять выделение", Modifier.size(18.dp), tint = Color.White)
                }
            }
        }
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
private fun GalleryDatePicker(range: GalleryDateRange, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val selectableDates = remember(range) { object : SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long): Boolean = range.contains(
            Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate())
        override fun isSelectableYear(year: Int): Boolean = year in range.years
    } }
    val state = rememberDatePickerState(yearRange = range.years, selectableDates = selectableDates)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { millis ->
                    // Material DatePicker encodes dates in UTC, not the phone's current timezone.
                    val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    if (range.contains(date)) onPick(date)
                }
            }, enabled = state.selectedDateMillis?.let { millis ->
                range.contains(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
            } == true) { Text("Перейти") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    ) {
        DatePicker(state = state)
    }
}

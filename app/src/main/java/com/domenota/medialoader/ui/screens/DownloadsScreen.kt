package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import com.domenota.medialoader.ui.components.DownloadGroupCard
import com.domenota.medialoader.data.catalog.runFileBatch
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.ui.components.AppCard
import com.domenota.medialoader.ui.components.DownloadRow
import com.domenota.medialoader.ui.components.DownloadThumbnail
import com.domenota.medialoader.ui.components.EmptyStateCard
import com.domenota.medialoader.ui.components.SectionTitle
import com.domenota.medialoader.ui.components.SourceIcon
import com.domenota.medialoader.ui.model.HistorySearch
import com.domenota.medialoader.ui.model.ThumbnailPrefetchPolicy
import com.domenota.medialoader.ui.model.DownloadUiItem
import com.domenota.medialoader.ui.model.DownloadUiState
import com.domenota.medialoader.ui.model.DownloadCatalogSelection
import com.domenota.medialoader.ui.model.MediaKind
import com.domenota.medialoader.ui.model.VkDownloadGroup
import com.domenota.medialoader.core.database.CatalogFolderEntity
import com.domenota.medialoader.core.database.CatalogAssignmentEntity
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import com.domenota.medialoader.ui.components.prepareDownloadThumbnail

private enum class DownloadSourceFilter(
    val label: String,
    val preferenceKey: String,
) {
    INSTAGRAM("Instagram", "downloads_filter_instagram"),
    YOUTUBE("YouTube", "downloads_filter_youtube"),
    VK("VK", "downloads_filter_vk"),
    RUTUBE("RUTUBE", "downloads_filter_rutube"),
    OTHER("Другие", "downloads_filter_other"),
}

private enum class DownloadTypeFilter(
    val label: String,
    val preferenceKey: String,
    val kind: MediaKind,
    val icon: ImageVector,
) {
    MUSIC("Музыка", "downloads_type_audio", MediaKind.AUDIO, Icons.Rounded.MusicNote),
    VIDEO("Видео", "downloads_type_video", MediaKind.VIDEO, Icons.Rounded.Videocam),
    PHOTO("Фото", "downloads_type_photo", MediaKind.IMAGE, Icons.Rounded.Image),
}

private fun sourceOf(providerId: String?): DownloadSourceFilter = when (providerId?.lowercase()) {
    "instagram" -> DownloadSourceFilter.INSTAGRAM
    "youtube" -> DownloadSourceFilter.YOUTUBE
    "vk" -> DownloadSourceFilter.VK
    "rutube" -> DownloadSourceFilter.RUTUBE
    else -> DownloadSourceFilter.OTHER
}

@Composable
fun DownloadsScreen(
    items: List<DownloadUiItem>,
    scrollToTopRequest: Int = 0,
    catalogFolders: List<CatalogFolderEntity> = emptyList(),
    catalogAssignments: List<CatalogAssignmentEntity> = emptyList(),
    onCreateFolder: suspend (String) -> String = { "" },
    onRenameFolder: suspend (String, String) -> Unit = { _, _ -> },
    onDeleteFolder: suspend (String) -> Unit = {},
    onSetFolder: suspend (String, String?) -> Unit = { _, _ -> },
    onToggleFavorite: suspend (String) -> Unit = {},
    onOpenDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onShareDownload: (String) -> Unit,
    onHideDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onReanalyzeMissing: (String) -> Unit,
    onRenameDownload: (String, String) -> Unit,
    recognitionEnabled: Boolean = false,
    onRecognizeDownload: (String) -> Unit = {},
    onOpenPrivate: () -> Unit = {},
    onMoveToPrivate: (String) -> Unit = {},
    onMoveGroupToPrivate: (List<String>) -> Unit = {},
    onSetFavorite: suspend (String, Boolean) -> Unit = { _, _ -> },
    onDeleteGroupFile: suspend (String) -> Unit = {},
    onOpenGrid: ((String, List<String>) -> Unit)? = null,
) {
    val context = LocalContext.current
    val listScrollState = rememberLazyListState()
    LaunchedEffect(listScrollState) {
        try {
            snapshotFlow { listScrollState.isScrollInProgress }.collectLatest { scrolling ->
                com.domenota.medialoader.data.playback.BackgroundMediaWork.setBusy("history-scroll", scrolling)
            }
        } finally {
            com.domenota.medialoader.data.playback.BackgroundMediaWork.setBusy("history-scroll", false)
        }
    }
    var expandedGroups by rememberSaveable { mutableStateOf(setOf<String>()) }
    var groupLimits by rememberSaveable { mutableStateOf(mapOf<String, Int>()) }
    val toggleGroup: (String) -> Unit = { id ->
        expandedGroups = if (id in expandedGroups) expandedGroups - id else expandedGroups + id
    }
    val showMore: (String) -> Unit = { id -> groupLimits = groupLimits + (id to ((groupLimits[id] ?: 24) + 24)) }
    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequest > 0) listScrollState.animateScrollToItem(0)
    }
    val prefs = remember(context) { context.getSharedPreferences("ui", 0) }
    var gridMode by rememberSaveable { mutableStateOf(prefs.getBoolean("downloads_grid", false)) }
    var instagramEnabled by rememberSaveable { mutableStateOf(prefs.getBoolean(DownloadSourceFilter.INSTAGRAM.preferenceKey, true)) }
    var youtubeEnabled by rememberSaveable { mutableStateOf(prefs.getBoolean(DownloadSourceFilter.YOUTUBE.preferenceKey, true)) }
    var vkEnabled by rememberSaveable { mutableStateOf(prefs.getBoolean(DownloadSourceFilter.VK.preferenceKey, true)) }
    var rutubeEnabled by rememberSaveable { mutableStateOf(prefs.getBoolean(DownloadSourceFilter.RUTUBE.preferenceKey, true)) }
    var otherEnabled by rememberSaveable { mutableStateOf(prefs.getBoolean(DownloadSourceFilter.OTHER.preferenceKey, true)) }
    var musicEnabled by rememberSaveable { mutableStateOf(prefs.getBoolean(DownloadTypeFilter.MUSIC.preferenceKey, true)) }
    var videoEnabled by rememberSaveable { mutableStateOf(prefs.getBoolean(DownloadTypeFilter.VIDEO.preferenceKey, true)) }
    var photoEnabled by rememberSaveable { mutableStateOf(prefs.getBoolean(DownloadTypeFilter.PHOTO.preferenceKey, true)) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var selectedCatalog by rememberSaveable { mutableStateOf(CATALOG_ALL) }
    var folderSheetOpen by rememberSaveable { mutableStateOf(false) }
    var folderTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var folderGroupTargets by remember { mutableStateOf<List<String>>(emptyList()) }
    var deleteGroupTargets by remember { mutableStateOf<List<String>>(emptyList()) }
    var groupBusy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val assignmentById = remember(catalogAssignments) { catalogAssignments.associateBy { it.downloadId } }
    val folderNames = remember(catalogFolders) { catalogFolders.associate { it.id to it.name } }

    fun groupAction(ids: List<String>, operation: suspend (String) -> Unit) {
        if (groupBusy) return
        groupBusy = true
        scope.launch {
            try {
                val result = runFileBatch(ids, operation)
                Toast.makeText(context, result.summary, Toast.LENGTH_LONG).show()
            } finally { groupBusy = false }
        }
    }
    if (groupBusy) AlertDialog(onDismissRequest = {}, confirmButton = {},
        text = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(Modifier.size(28.dp)); Text("Обрабатываем группу…")
        } })
    if (deleteGroupTargets.isNotEmpty()) AlertDialog(onDismissRequest = { deleteGroupTargets = emptyList() },
        title = { Text(com.domenota.medialoader.ui.components.deleteSelectionTitle(deleteGroupTargets.size)) },
        confirmButton = { TextButton(onClick = { val ids = deleteGroupTargets; deleteGroupTargets = emptyList(); groupAction(ids) { id ->
                require(items.firstOrNull { it.id == id }?.state == DownloadUiState.COMPLETED) { "Дождитесь завершения загрузки" }
                onDeleteGroupFile(id)
            } }) {
            Text("Удалить", color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { deleteGroupTargets = emptyList() }) { Text("Отмена") } })

    if (folderSheetOpen) DownloadFolderSheet(
        folders = catalogFolders,
        assignments = assignmentById,
        items = items,
        selectedKey = selectedCatalog,
        targetDownloadId = folderTargetId,
        onDismiss = { folderSheetOpen = false; folderTargetId = null; folderGroupTargets = emptyList() },
        onSelect = { selectedCatalog = it },
        onCreate = onCreateFolder,
        onRename = onRenameFolder,
        onDelete = onDeleteFolder,
        onAssign = { id, folder ->
            if (folderGroupTargets.isEmpty()) onSetFolder(id, folder)
            else {
                val result = runFileBatch(folderGroupTargets) { onSetFolder(it, folder) }
                Toast.makeText(context, result.summary, Toast.LENGTH_LONG).show()
                check(result.failed == 0) { result.summary }
            }
        },
    )

    val hasOther = items.any { sourceOf(it.providerId) == DownloadSourceFilter.OTHER }
    val visibleSources = buildList {
        add(DownloadSourceFilter.INSTAGRAM)
        add(DownloadSourceFilter.YOUTUBE)
        add(DownloadSourceFilter.VK)
        add(DownloadSourceFilter.RUTUBE)
        if (hasOther) add(DownloadSourceFilter.OTHER)
    }

    fun isEnabled(source: DownloadSourceFilter): Boolean = when (source) {
        DownloadSourceFilter.INSTAGRAM -> instagramEnabled
        DownloadSourceFilter.YOUTUBE -> youtubeEnabled
        DownloadSourceFilter.VK -> vkEnabled
        DownloadSourceFilter.RUTUBE -> rutubeEnabled
        DownloadSourceFilter.OTHER -> otherEnabled
    }

    fun setEnabled(source: DownloadSourceFilter, enabled: Boolean) {
        val enabledCount = visibleSources.count(::isEnabled)
        if (!enabled && isEnabled(source) && enabledCount <= 1) return

        when (source) {
            DownloadSourceFilter.INSTAGRAM -> instagramEnabled = enabled
            DownloadSourceFilter.YOUTUBE -> youtubeEnabled = enabled
            DownloadSourceFilter.VK -> vkEnabled = enabled
            DownloadSourceFilter.RUTUBE -> rutubeEnabled = enabled
            DownloadSourceFilter.OTHER -> otherEnabled = enabled
        }
        prefs.edit().putBoolean(source.preferenceKey, enabled).apply()
    }

    fun enableAll() {
        instagramEnabled = true
        youtubeEnabled = true
        vkEnabled = true
        rutubeEnabled = true
        if (hasOther) otherEnabled = true
        prefs.edit()
            .putBoolean(DownloadSourceFilter.INSTAGRAM.preferenceKey, true)
            .putBoolean(DownloadSourceFilter.YOUTUBE.preferenceKey, true)
            .putBoolean(DownloadSourceFilter.VK.preferenceKey, true)
            .putBoolean(DownloadSourceFilter.RUTUBE.preferenceKey, true)
            .putBoolean(DownloadSourceFilter.OTHER.preferenceKey, true)
            .apply()
    }

    fun isTypeEnabled(type: DownloadTypeFilter): Boolean = when (type) {
        DownloadTypeFilter.MUSIC -> musicEnabled
        DownloadTypeFilter.VIDEO -> videoEnabled
        DownloadTypeFilter.PHOTO -> photoEnabled
    }

    fun setTypeEnabled(type: DownloadTypeFilter, enabled: Boolean) {
        if (!enabled && isTypeEnabled(type) && DownloadTypeFilter.entries.count(::isTypeEnabled) <= 1) return
        when (type) {
            DownloadTypeFilter.MUSIC -> musicEnabled = enabled
            DownloadTypeFilter.VIDEO -> videoEnabled = enabled
            DownloadTypeFilter.PHOTO -> photoEnabled = enabled
        }
        prefs.edit().putBoolean(type.preferenceKey, enabled).apply()
    }

    fun enableAllTypes() {
        musicEnabled = true
        videoEnabled = true
        photoEnabled = true
        prefs.edit()
            .putBoolean(DownloadTypeFilter.MUSIC.preferenceKey, true)
            .putBoolean(DownloadTypeFilter.VIDEO.preferenceKey, true)
            .putBoolean(DownloadTypeFilter.PHOTO.preferenceKey, true)
            .apply()
    }

    fun selectOnlyType(type: DownloadTypeFilter) {
        musicEnabled = type == DownloadTypeFilter.MUSIC
        videoEnabled = type == DownloadTypeFilter.VIDEO
        photoEnabled = type == DownloadTypeFilter.PHOTO
        prefs.edit()
            .putBoolean(DownloadTypeFilter.MUSIC.preferenceKey, musicEnabled)
            .putBoolean(DownloadTypeFilter.VIDEO.preferenceKey, videoEnabled)
            .putBoolean(DownloadTypeFilter.PHOTO.preferenceKey, photoEnabled)
            .apply()
    }

    val allEnabled = visibleSources.all(::isEnabled)
    val allTypesEnabled = DownloadTypeFilter.entries.all(::isTypeEnabled)
    val filteredItems = remember(items, instagramEnabled, youtubeEnabled, vkEnabled, rutubeEnabled,
        otherEnabled, musicEnabled, videoEnabled, photoEnabled) { items.filter { item ->
        isEnabled(sourceOf(item.providerId)) &&
            DownloadTypeFilter.entries.any { it.kind == item.kind && isTypeEnabled(it) }
    } }
    val groups = remember(filteredItems) { filteredItems.groupBy(VkDownloadGroup::key).values.toList() }
    val active = remember(groups) { groups.filter { group -> group.any { it.state == DownloadUiState.QUEUED || it.state == DownloadUiState.DOWNLOADING } } }
    val history = remember(groups, active, selectedCatalog, assignmentById) { (groups - active.toSet()).map { group ->
        group.filter { item ->
            DownloadCatalogSelection.includesHistoryItem(
                selectedCatalog, item.state, assignmentById[item.id])
        }
    }.filter { it.isNotEmpty() } }
    val searchedHistory = remember(history, searchText) { HistorySearch.filterGroups(history, searchText) }

    val thumbnailRows = remember(active, searchedHistory, expandedGroups, groupLimits, gridMode) {
        (active + if (gridMode) emptyList() else searchedHistory).flatMap { group ->
            val id = VkDownloadGroup.key(group.first())
            if (group.size == 1) listOf("file:${group.first().id}" to group.first())
            else listOf("group:$id" to group.first()) + if (id in expandedGroups) {
                group.take(groupLimits[id] ?: 24).map { "child:${it.id}" to it }
            } else emptyList()
        } + if (gridMode) searchedHistory.flatten().chunked(4).flatMap { row ->
            row.map { "grid:${row.first().id}" to it }
        } else emptyList()
    }
    LaunchedEffect(thumbnailRows, listScrollState) {
        val indexByKey = thumbnailRows.mapIndexed { index, row -> row.first to index }.toMap()
        var previous = 0
        snapshotFlow { listScrollState.layoutInfo.visibleItemsInfo.map { it.key } }.collectLatest { keys ->
            val visible = keys.mapNotNull(indexByKey::get)
            val first = visible.minOrNull() ?: 0
            val last = visible.maxOrNull() ?: -1
            val candidates = ThumbnailPrefetchPolicy.indices(first, last, previous, thumbnailRows.size)
            previous = first
            candidates.distinct().forEach { index ->
                thumbnailRows.getOrNull(index)?.second?.let { row ->
                    row.savedUri?.let { prepareDownloadThumbnail(context, it, row.kind, row.previewUrl) }
                }
            }
        }
    }

    LazyColumn(
        state = listScrollState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
    ) {
      item(key = "header", contentType = "header") {
       Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Загрузки",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onOpenPrivate, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.Lock, contentDescription = "Личное")
            }
            IconButton(onClick = { folderTargetId = null; folderGroupTargets = emptyList(); folderSheetOpen = true },
                modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = "Папки загрузок",
                    tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(
                onClick = {
                    searchVisible = !searchVisible
                    if (!searchVisible) searchText = ""
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    if (searchVisible) Icons.Rounded.Close else Icons.Rounded.Search,
                    contentDescription = if (searchVisible) "Закрыть поиск" else "Поиск по загрузкам",
                )
            }
        }
        if (searchVisible) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchText.isNotEmpty()) IconButton(onClick = { searchText = "" }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Очистить поиск")
                    }
                },
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceChip("Все", null, allEnabled, onClick = ::enableAll)
            visibleSources.forEach { source ->
                SourceChip(source.label,
                    source.name.lowercase().takeIf { source != DownloadSourceFilter.OTHER },
                    !allEnabled && isEnabled(source), onClick = { setEnabled(source, !isEnabled(source)) })
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceChip("Все файлы", null, allTypesEnabled, onClick = ::enableAllTypes)
            DownloadTypeFilter.entries.forEach { type ->
                val selected = !allTypesEnabled && isTypeEnabled(type)
                SourceChip(type.label, null, selected, onClick = {
                    if (selected && DownloadTypeFilter.entries.count(::isTypeEnabled) == 1) {
                        enableAllTypes()
                    } else {
                        selectOnlyType(type)
                    }
                })
            }
        }
        Spacer(Modifier.height(20.dp))
        SectionTitle(title = "Активные")
        Spacer(Modifier.height(12.dp))
       }
      }
        if (active.isEmpty()) {
          item(key = "empty-active", contentType = "empty") {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (items.isNotEmpty() && filteredItems.isEmpty()) {
                        "Нет задач по выбранным фильтрам"
                    } else {
                        "Сейчас ничего не скачивается"
                    },
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }
          }
        } else {
            DownloadRows(active, onOpenDownload, onCancelDownload, onShareDownload, onHideDownload, onRetryDownload, onReanalyzeMissing, onRenameDownload, recognitionEnabled, onRecognizeDownload,
                expandedGroups = expandedGroups, groupLimits = groupLimits, onExpand = toggleGroup, onMore = showMore,
                onMoveToPrivate = onMoveToPrivate,
                onGroupFavorite = { group ->
                    val favorite = !group.all { assignmentById[it.id]?.isFavorite == true }
                    groupAction(group.map { it.id }) { onSetFavorite(it, favorite) }
                },
                onGroupFolder = { ids -> folderGroupTargets = ids; folderTargetId = ids.firstOrNull(); folderSheetOpen = true },
                onGroupMove = onMoveGroupToPrivate, onGroupDelete = { deleteGroupTargets = it }, groupActionsEnabled = !groupBusy)
        }

      item(key = "history-header", contentType = "header") {
       Column {
        Spacer(Modifier.height(28.dp))
        val catalogLabel = when (selectedCatalog) {
            CATALOG_ALL -> "История"
            CATALOG_FAVORITES -> "Избранное"
            CATALOG_UNFILED -> "Без папки"
            else -> folderNames[selectedCatalog.removePrefix("folder:")] ?: "История"
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(catalogLabel, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            IconButton(onClick = {
                gridMode = !gridMode
                prefs.edit().putBoolean("downloads_grid", gridMode).apply()
            }) {
                Icon(if (gridMode) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                    contentDescription = if (gridMode) "Показать списком" else "Показать сеткой")
            }
            if (selectedCatalog != CATALOG_ALL) TextButton(onClick = {
                selectedCatalog = CATALOG_ALL
            }) { Text("Все файлы") }
        }
        Spacer(Modifier.height(12.dp))
       }
      }
        if (searchedHistory.isEmpty()) {
          item(key = "empty-history", contentType = "empty") {
            EmptyStateCard(
                icon = if (searchText.isNotBlank()) Icons.Rounded.Search else Icons.Rounded.Download,
                title = when {
                    searchText.isNotBlank() -> "Ничего не найдено"
                    items.isNotEmpty() && filteredItems.isEmpty() -> "Нет файлов по выбранным фильтрам"
                    selectedCatalog != CATALOG_ALL -> "Здесь пока нет файлов"
                    else -> "История пока пустая"
                },
                text = when {
                    searchText.isNotBlank() -> "Измените запрос или фильтры"
                    items.isNotEmpty() && filteredItems.isEmpty() -> "Измените источник или тип файлов"
                    selectedCatalog != CATALOG_ALL -> "Добавьте файлы через меню загрузки"
                    else -> "Скачанные фото, видео и аудио будут собраны здесь"
                },
            )
          }
        } else {
            DownloadRows(searchedHistory, { id ->
                if (gridMode && onOpenGrid != null) onOpenGrid(id, searchedHistory.flatten().map { it.id })
                else onOpenDownload(id)
            }, onCancelDownload, onShareDownload, onHideDownload, onRetryDownload, onReanalyzeMissing, onRenameDownload, recognitionEnabled, onRecognizeDownload,
                assignmentById, folderNames,
                onFavorite = { id -> scope.launch {
                    runCatching { onToggleFavorite(id) }.onFailure {
                        Toast.makeText(context, it.message ?: "Не удалось изменить избранное",
                            Toast.LENGTH_SHORT).show()
                    }
                } },
                onChooseFolder = { id -> folderTargetId = id; folderGroupTargets = emptyList(); folderSheetOpen = true },
                expandedGroups = expandedGroups, groupLimits = groupLimits, onExpand = toggleGroup, onMore = showMore,
                onMoveToPrivate = onMoveToPrivate, grid = gridMode,
                onGroupFavorite = { group ->
                    val favorite = !group.all { assignmentById[it.id]?.isFavorite == true }
                    groupAction(group.map { it.id }) { onSetFavorite(it, favorite) }
                },
                onGroupFolder = { ids -> folderGroupTargets = ids; folderTargetId = ids.firstOrNull(); folderSheetOpen = true },
                onGroupMove = onMoveGroupToPrivate, onGroupDelete = { deleteGroupTargets = it }, groupActionsEnabled = !groupBusy)
        }
    }
}

private fun LazyListScope.DownloadRows(
    items: List<List<DownloadUiItem>>,
    onOpenDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onShareDownload: (String) -> Unit,
    onHideDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onReanalyzeMissing: (String) -> Unit,
    onRenameDownload: (String, String) -> Unit,
    recognitionEnabled: Boolean,
    onRecognizeDownload: (String) -> Unit,
    assignments: Map<String, CatalogAssignmentEntity> = emptyMap(),
    folderNames: Map<String, String> = emptyMap(),
    onFavorite: ((String) -> Unit)? = null,
    onChooseFolder: ((String) -> Unit)? = null,
    expandedGroups: Set<String>,
    groupLimits: Map<String, Int>,
    onExpand: (String) -> Unit,
    onMore: (String) -> Unit,
    onMoveToPrivate: (String) -> Unit,
    grid: Boolean = false,
    onGroupFavorite: (List<DownloadUiItem>) -> Unit,
    onGroupFolder: (List<String>) -> Unit,
    onGroupMove: (List<String>) -> Unit,
    onGroupDelete: (List<String>) -> Unit,
    groupActionsEnabled: Boolean,
) {
    if (grid) {
        val rows = items.flatten().chunked(4)
        items(rows, key = { "grid:${it.first().id}" }, contentType = { "grid" }) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { entry ->
                    key(entry.id) { DownloadItemRow(entry, onOpenDownload, onCancelDownload, onShareDownload,
                        onHideDownload, onRetryDownload, onReanalyzeMissing, onRenameDownload,
                        recognitionEnabled, onRecognizeDownload, assignments, folderNames,
                        onFavorite, onChooseFolder, onMoveToPrivate, compact = true, modifier = Modifier.weight(1f)) }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(4.dp))
        }
        return
    }
    items.forEach { group ->
        val groupKey = VkDownloadGroup.key(group.first())
        if (group.size > 1) {
          val expanded = groupKey in expandedGroups
          val visibleCount = groupLimits[groupKey] ?: 24
          item(key = "group:$groupKey", contentType = "group") {
            DownloadGroupCard(group, expanded, { onExpand(groupKey) }, onOpenDownload,
                allFavorite = group.all { assignments[it.id]?.isFavorite == true },
                onFavorite = { onGroupFavorite(group) },
                onChooseFolder = { onGroupFolder(group.map { it.id }) },
                onMoveToPrivate = { onGroupMove(group.map { it.id }) },
                onDelete = { onGroupDelete(group.map { it.id }) }, actionsEnabled = groupActionsEnabled)
            Spacer(Modifier.height(8.dp))
          }
            if (expanded) {
                items(group.take(visibleCount), key = { "child:${it.id}" }, contentType = { "file" }) { item ->
                    DownloadItemRow(item, onOpenDownload, onCancelDownload, onShareDownload,
                        onHideDownload, onRetryDownload, onReanalyzeMissing, onRenameDownload,
                        recognitionEnabled, onRecognizeDownload, assignments, folderNames,
                        onFavorite, onChooseFolder, onMoveToPrivate)
                    Spacer(Modifier.height(8.dp))
                }
                if (visibleCount < group.size) item(key = "more:$groupKey", contentType = "more") {
                    TextButton(onClick = { onMore(groupKey) }) { Text("Показать ещё ${minOf(24, group.size - visibleCount)}") }
                }
            }
        } else {
          item(key = "file:${group.single().id}", contentType = "file") {
            DownloadItemRow(group.single(), onOpenDownload, onCancelDownload, onShareDownload,
                onHideDownload, onRetryDownload, onReanalyzeMissing, onRenameDownload,
                recognitionEnabled, onRecognizeDownload, assignments, folderNames,
                onFavorite, onChooseFolder, onMoveToPrivate)
            Spacer(Modifier.height(10.dp))
          }
        }
    }
}

@Composable
private fun DownloadItemRow(
    item: DownloadUiItem,
    onOpenDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onShareDownload: (String) -> Unit,
    onHideDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onReanalyzeMissing: (String) -> Unit,
    onRenameDownload: (String, String) -> Unit,
    recognitionEnabled: Boolean,
    onRecognizeDownload: (String) -> Unit,
    assignments: Map<String, CatalogAssignmentEntity>,
    folderNames: Map<String, String>,
    onFavorite: ((String) -> Unit)?,
    onChooseFolder: ((String) -> Unit)?,
    onMoveToPrivate: (String) -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    DownloadRow(
        item = item,
        compact = compact,
        modifier = modifier,
        onOpen = { onOpenDownload(item.id) },
        onCancel = { onCancelDownload(item.id) },
        onShare = { onShareDownload(item.id) },
        onHide = { onHideDownload(item.id) },
        onRetry = { onRetryDownload(item.id) },
        onReanalyzeMissing = { onReanalyzeMissing(item.id) },
        onRename = { onRenameDownload(item.id, it) },
        recognitionEnabled = recognitionEnabled,
        onRecognize = { onRecognizeDownload(item.id) },
        favorite = assignments[item.id]?.isFavorite == true,
        folderLabel = assignments[item.id]?.folderId?.let(folderNames::get),
        onToggleFavorite = onFavorite?.let { action -> { action(item.id) } },
        onChooseFolder = onChooseFolder?.let { action -> { action(item.id) } },
        onMoveToPrivate = { onMoveToPrivate(item.id) },
    )
}

@Composable
private fun SourceChip(label: String, source: String?, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    Row(Modifier.background(color, shape)
        .border(1.dp, if (active) Color.Transparent else MaterialTheme.colorScheme.outline, shape)
        .clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        if (source != null) SourceIcon(source, 22.dp)
        Text(label, color = if (active) MaterialTheme.colorScheme.onPrimary else
            MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge)
    }
}

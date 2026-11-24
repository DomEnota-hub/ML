package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.ui.components.DownloadThumbnail
import com.domenota.medialoader.ui.model.toUi
import com.domenota.medialoader.data.archive.SimpleArchiveRecord
import com.domenota.medialoader.data.catalog.visibility.SelectiveArchiveQaEntry
import com.domenota.medialoader.ui.components.ArchiveActionIcon

/** Ordinary, unlocked archive. No vault or private-access state is consumed here.
 * The host is responsible for live, authorized preview and actual restoration.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SimpleArchiveScreen(
    records: List<SimpleArchiveRecord>,
    onClose: () -> Unit,
    onOpen: (SimpleArchiveRecord) -> Unit,
    onRestore: (Set<String>) -> Unit,
    busy: Boolean = false,
    message: String? = null,
    liveRows: Map<String, DownloadEntity> = emptyMap(),
    legacyRecovery: List<SelectiveArchiveQaEntry> = emptyList(),
    legacyNames: Map<String,String> = emptyMap(),
    legacyBusy: Boolean = false,
    legacyMessage: String? = null,
    onLegacyRestore: ((String) -> Unit)? = null,
    legacyHiddenFolders: Set<String> = emptySet(),
    onLegacyFolderRestore: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("ui", 0) }
    var gridMode by rememberSaveable { mutableStateOf(prefs.getBoolean("archive_grid", false)) }
    var type by remember { mutableStateOf<MediaType?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val filtered = remember(records, type) {
        records.filter { type == null || it.mediaType == type }.sortedByDescending { it.archivedAtMillis }
    }
    val liveIds = remember(filtered) { filtered.mapTo(mutableSetOf()) { it.catalogId } }
    val chosen = selected.intersect(liveIds)
    LaunchedEffect(liveIds) { selected = selected.intersect(liveIds) }
    fun toggle(id: String) {
        if (!busy && id in liveIds) selected = if (id in selected) selected - id else selected + id
    }
    var expandedGroups by remember { mutableStateOf(emptySet<String>()) }
    val grouped = remember(filtered) { filtered.filter { it.group != null }.groupBy {
        val group = checkNotNull(it.group)
        group.kind.name + ":" + group.key
    } }
    val ungrouped = remember(filtered) { filtered.filter { it.group == null } }
        @Composable
        fun itemRow(item: SimpleArchiveRecord) {
                Row(
                    Modifier.fillMaxWidth().combinedClickable(enabled = !busy,
                        onClick = { if (chosen.isEmpty()) onOpen(item) else toggle(item.catalogId) },
                        onLongClick = { toggle(item.catalogId) }).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val liveRow = liveRows[item.catalogId]
                    if (liveRow != null) DownloadThumbnail(liveRow.savedUri, liveRow.previewUrl,
                        liveRow.toUi().kind, Modifier.size(56.dp))
                    else Icon(when (item.mediaType) {
                        MediaType.PHOTO -> Icons.Rounded.Image
                        MediaType.VIDEO -> Icons.Rounded.Movie
                        MediaType.AUDIO -> Icons.Rounded.MusicNote
                    }, null, Modifier.size(32.dp))
                    Text(item.displayName, Modifier.weight(1f), maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { toggle(item.catalogId) }, enabled = !busy) {
                        Icon(if (item.catalogId in chosen) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                            if (item.catalogId in chosen) "Снять выбор" else "Выбрать",
                            tint = if (item.catalogId in chosen) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
    @Composable
    fun itemCell(item: SimpleArchiveRecord) {
        if (!gridMode) {
            itemRow(item)
        } else {
            val checked = item.catalogId in chosen
            Column(Modifier.padding(2.dp).testTag("archive-grid-${item.catalogId}")) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f)
                    .combinedClickable(enabled = !busy,
                        onClick = { if (chosen.isEmpty()) onOpen(item) else toggle(item.catalogId) },
                        onLongClick = { toggle(item.catalogId) })
                    .semantics { contentDescription = item.displayName }) {
                    val row = liveRows[item.catalogId]
                    if (row != null) DownloadThumbnail(row.savedUri, row.previewUrl, row.toUi().kind, Modifier.fillMaxSize())
                    else Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(when (item.mediaType) {
                                MediaType.PHOTO -> Icons.Rounded.Image
                                MediaType.VIDEO -> Icons.Rounded.Movie
                                MediaType.AUDIO -> Icons.Rounded.MusicNote
                            }, null, Modifier.size(32.dp))
                        }
                    }
                    Box(Modifier.align(Alignment.TopEnd).size(48.dp)
                        .toggleable(value = checked, enabled = !busy, role = Role.Checkbox,
                            onValueChange = { toggle(item.catalogId) })
                        .semantics { contentDescription = if (checked) "Снять выбор" else "Выбрать" },
                        contentAlignment = Alignment.Center) {
                        Surface(shape = androidx.compose.foundation.shape.CircleShape,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = .9f)) {
                            Icon(if (checked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                null, Modifier.size(24.dp), tint = if (checked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                Text(item.displayName, Modifier.padding(horizontal = 2.dp), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    androidx.activity.compose.BackHandler(chosen.isNotEmpty() && !busy) { selected = emptySet() }
    Column(Modifier.fillMaxSize().testTag("simple-archive-screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
            Text("Архив", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold)
            IconButton(onClick = {
                gridMode = !gridMode
                prefs.edit().putBoolean("archive_grid", gridMode).apply()
            }, enabled = !busy) {
                Icon(if (gridMode) Icons.Rounded.ViewList else Icons.Rounded.GridView,
                    if (gridMode) "Показать списком" else "Показать сеткой",
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text("Файлов: ${records.size}", Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null to "Все", MediaType.PHOTO to "Фото", MediaType.VIDEO to "Видео",
                MediaType.AUDIO to "Музыка").forEach { (value, label) ->
                FilterChip(selected = type == value, onClick = { type = value; selected = emptySet() },
                    label = { Text(label) }, enabled = !busy)
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message != null) Text(message, Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall)
        if (filtered.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selected = if (chosen == liveIds) emptySet() else liveIds.toSet() },
                    enabled = !busy) { Text(if (chosen == liveIds) "Снять выбор" else "Выбрать все") }
            }
        }
        if (legacyMessage != null) Text(legacyMessage, Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall)
        val hasLegacy = type == null && (legacyRecovery.isNotEmpty() || legacyHiddenFolders.isNotEmpty())
        if (filtered.isEmpty() && !hasLegacy) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ArchiveActionIcon(contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Здесь пока пусто", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyVerticalGrid(columns = GridCells.Fixed(if (gridMode) 4 else 1),
                modifier = Modifier.weight(1f).testTag("archive-items")) {
                if (hasLegacy) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Text("Ранее скрытые файлы", Modifier.padding(16.dp), fontWeight = FontWeight.SemiBold) }
                    items(legacyRecovery, key = { "legacy:" + it.folderId }, span = { GridItemSpan(maxLineSpan) }) { entry ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(legacyNames[entry.folderId] ?: "Скрытые файлы")
                                Text(if (entry.needsReview) "Требуется проверка" else "Файлов: ${entry.hidden}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { onLegacyRestore?.invoke(entry.folderId) },
                                enabled = !legacyBusy && !busy && !entry.needsReview && onLegacyRestore != null) { Text("Вернуть") }
                        }
                    }
                }
                if (hasLegacy) items(legacyHiddenFolders.sorted(), key = { "folder:" + it }, span = { GridItemSpan(maxLineSpan) }) { folderId ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(legacyNames[folderId] ?: "Скрытая папка")
                            Text("Ранее скрытая папка", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onLegacyFolderRestore?.invoke(folderId) },
                            enabled = !busy && !legacyBusy && onLegacyFolderRestore != null) { Text("Вернуть") }
                    }
                }
                items(ungrouped, key = { "file:" + it.catalogId }) { item -> itemCell(item) }
                grouped.forEach { (key, members) ->
                    val expanded = key in expandedGroups
                    val ids = members.mapTo(mutableSetOf()) { it.catalogId }
                    val allSelected = chosen.containsAll(ids)
                    item(key = "group:" + key, span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).combinedClickable(enabled = !busy,
                                onClick = { expandedGroups = if (expanded) expandedGroups - key else expandedGroups + key },
                                onLongClick = { selected = if (allSelected) selected - ids else selected + ids })
                                .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)) {
                                    Text(members.first().group?.name.orEmpty(), maxLines = 2,
                                        overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                    Text("Файлов: ${members.size}", style = MaterialTheme.typography.bodySmall)
                                }
                                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    if (expanded) "Свернуть" else "Развернуть")
                            }
                            IconButton(onClick = { selected = if (allSelected) selected - ids else selected + ids },
                                enabled = !busy) {
                                Icon(if (allSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                    if (allSelected) "Снять выбор группы" else "Выбрать группу",
                                    tint = if (allSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (expanded) items(members, key = { "file:" + it.catalogId }) { item -> itemCell(item) }
                }
            }
        }
        if (chosen.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 5.dp) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Выбрано: ${chosen.size}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Button(onClick = { onRestore(chosen) }, enabled = !busy,
                    modifier = Modifier.testTag("simple-archive-restore")) { Text("Вернуть") }
                IconButton(onClick = { selected = emptySet() }, enabled = !busy) {
                    Icon(Icons.Rounded.Close, "Отменить выбор")
                }
            }
        }
    }
}

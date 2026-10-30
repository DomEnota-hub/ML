package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.ui.components.EditorWindow
import com.domenota.medialoader.ui.components.DownloadThumbnail
import com.domenota.medialoader.ui.model.DownloadUiItem
import com.domenota.medialoader.ui.model.DownloadUiState
import com.domenota.medialoader.ui.model.MediaKind
import java.util.Locale

internal enum class EditorPickerSort(val title: String) {
    NEWEST("Сначала новые"), OLDEST("Сначала старые"),
    NAME_ASC("По названию А–Я"), NAME_DESC("По названию Я–А")
}

/** Date labels from UiMappers: dd.MM.yyyy HH:mm; stable numeric ordering. */
internal fun editorPickerDateKey(label: String?): String {
    val value = label.orEmpty()
    return if (value.length >= 16 && value[2] == '.' && value[5] == '.' &&
        value[10] == ' ' && value[13] == ':'
    ) value.substring(6, 10) + value.substring(3, 5) +
        value.substring(0, 2) + value.substring(11, 13) + value.substring(14, 16)
    else ""
}

internal fun editorPickerChoices(
    files: List<DownloadUiItem>,
    kind: MediaKind,
    query: String,
    album: String?,
    albumsById: Map<String, List<String>>,
    sort: EditorPickerSort,
): List<DownloadUiItem> {
    val needle = query.trim()
    val matching = files.filter { row ->
        row.kind == kind && row.state == DownloadUiState.COMPLETED && row.savedUri != null &&
            (needle.isEmpty() || row.title.contains(needle, true) ||
                row.subtitle.contains(needle, true)) &&
            (album == null || (albumsById[row.id].orEmpty()
                .ifEmpty { listOf("Без альбома") }).contains(album))
    }.distinctBy { it.id }
    return when (sort) {
        EditorPickerSort.NEWEST -> matching.sortedWith(
            compareByDescending<DownloadUiItem> { editorPickerDateKey(it.dateLabel) }
                .thenBy { it.title.lowercase(Locale.ROOT) })
        EditorPickerSort.OLDEST -> matching.sortedWith(
            compareBy<DownloadUiItem> { editorPickerDateKey(it.dateLabel) }
                .thenBy { it.title.lowercase(Locale.ROOT) })
        EditorPickerSort.NAME_ASC -> matching.sortedWith(
            compareBy<DownloadUiItem> { it.title.lowercase(Locale.ROOT) }
                .thenByDescending { editorPickerDateKey(it.dateLabel) })
        EditorPickerSort.NAME_DESC -> matching.sortedWith(
            compareByDescending<DownloadUiItem> { it.title.lowercase(Locale.ROOT) }
                .thenByDescending { editorPickerDateKey(it.dateLabel) })
    }
}

/**
 * Full-height picker for large media libraries: groups, searching, sorting,
 * and 4-column view. Changes only the presentation, never source files.
 */
@Composable
internal fun EditorFilePickerDialog(
    kind: MediaKind,
    files: List<DownloadUiItem>,
    albumsById: Map<String, List<String>>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("editor_file_picker_v1", android.content.Context.MODE_PRIVATE)
    }
    var query by remember(kind) { mutableStateOf("") }
    var album by remember(kind) { mutableStateOf<String?>(null) }
    var albumMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var grid by remember(kind) { mutableStateOf(prefs.getBoolean("grid_${kind.name}", false)) }
    var sort by remember(kind) {
        mutableStateOf(EditorPickerSort.entries.firstOrNull {
            it.name == prefs.getString("sort_${kind.name}", EditorPickerSort.NEWEST.name)
        } ?: EditorPickerSort.NEWEST)
    }
    val available = remember(files, kind) {
        files.filter { it.kind == kind && it.state == DownloadUiState.COMPLETED &&
            !it.savedUri.isNullOrBlank() }.distinctBy { it.id }
    }
    val albums = remember(available, albumsById) {
        available.flatMap { albumsById[it.id].orEmpty().ifEmpty { listOf("Без альбома") } }
            .distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    val choices = remember(available, query, album, albumsById, sort) {
        editorPickerChoices(available, kind, query, album, albumsById, sort)
    }
    // Reuse the project's full-window dialog host: Compose 1.7.4 mismeasures
    // Dialog height with usePlatformDefaultWidth=false on some Android devices.
    EditorWindow(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
                Row(Modifier.fillMaxWidth().height(58.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.ArrowBack, "Закрыть выбор")
                    }
                    Text(when (kind) {
                        MediaKind.IMAGE -> "Выбрать фото"
                        MediaKind.VIDEO -> "Выбрать видео"
                        MediaKind.AUDIO -> "Выбрать аудио"
                    }, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        grid = !grid
                        prefs.edit().putBoolean("grid_${kind.name}", grid).apply()
                    }) {
                        Icon(if (grid) Icons.Rounded.ViewList else Icons.Rounded.GridView,
                            if (grid) "Переключить на список" else "Сетка 4 в ряд")
                    }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Поиск файла") }, singleLine = true,
                    placeholder = { Text("Название файла или исполнитель") },
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { albumMenu = true },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(album ?: "Все альбомы", maxLines = 1,
                                overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                        }
                        DropdownMenu(expanded = albumMenu, onDismissRequest = { albumMenu = false }) {
                            DropdownMenuItem(text = { Text("Все альбомы (${available.size})") },
                                onClick = { album = null; albumMenu = false })
                            albums.forEach { name ->
                                val total = available.count { name in albumsById[it.id].orEmpty()
                                    .ifEmpty { listOf("Без альбома") } }
                                DropdownMenuItem(text = { Text("$name ($total)", maxLines = 1) },
                                    onClick = { album = name; albumMenu = false })
                            }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { sortMenu = true },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Icon(Icons.Rounded.Sort, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(sort.title, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            EditorPickerSort.entries.forEach { option ->
                                DropdownMenuItem(text = { Text(option.title) }, onClick = {
                                    sort = option
                                    prefs.edit().putString("sort_${kind.name}", sort.name).apply()
                                    sortMenu = false
                                })
                            }
                        }
                    }
                }
                Text("Показано: ${choices.size} из ${available.size}",
                    modifier = Modifier.padding(vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (choices.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(if (available.isEmpty()) "Подходящих файлов пока нет."
                            else "Файлы по этим условиям не найдены.")
                    }
                } else if (grid) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4), modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        gridItems(choices, key = { it.id }) { file ->
                            Column(Modifier.fillMaxWidth().clickable { onSelect(file.id) }) {
                                DownloadThumbnail(file.savedUri, file.previewUrl, file.kind,
                                    Modifier.fillMaxWidth().aspectRatio(1f)
                                        .clip(RoundedCornerShape(9.dp)))
                                Text(file.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    fontSize = 10.sp, lineHeight = 12.sp,
                                    modifier = Modifier.padding(top = 3.dp))
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(choices, key = { it.id }) { file ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onSelect(file.id) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                DownloadThumbnail(file.savedUri, file.previewUrl, file.kind,
                                    Modifier.size(54.dp).clip(RoundedCornerShape(9.dp)))
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(file.title, maxLines = 2,
                                        style = MaterialTheme.typography.bodyMedium,
                                        overflow = TextOverflow.Ellipsis)
                                    Text(file.subtitle, maxLines = 1,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

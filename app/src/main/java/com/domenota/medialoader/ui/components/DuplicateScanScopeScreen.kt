package com.domenota.medialoader.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.library.DuplicateFolderPathResolver
import com.domenota.medialoader.data.library.DuplicateFolderScope
import com.domenota.medialoader.data.library.DuplicateFolderSort
import com.domenota.medialoader.data.library.DuplicateFolderTree

/** Same full-screen folder chooser for Gallery and Music.
 * Never expands scope from an ambiguous MediaStore bucket or display name.
 */
@Composable
internal fun DuplicateScanScopeScreen(
    rows: List<DownloadEntity>,
    mediaLabel: String,
    onDismiss: () -> Unit,
    onStart: (Set<String>?) -> Unit,
) {
    val context = LocalContext.current
    val folders = remember(rows) { DuplicateFolderScope.options(rows) }
    var choosing by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    var sortMode by remember { mutableStateOf(DuplicateFolderSort.FILE_COUNT) }
    var descending by remember { mutableStateOf(true) }
    var resolvedPaths by remember(rows) { mutableStateOf(emptyMap<String, String>()) }
    var resolvingPaths by remember(rows) { mutableStateOf(false) }
    LaunchedEffect(choosing, rows) {
        if (choosing && folders.any { it.key.startsWith("bucket:") }) {
            resolvingPaths = true
            try {
                resolvedPaths = DuplicateFolderPathResolver.resolve(context, rows)
            } finally {
                resolvingPaths = false
            }
        }
    }
    val options = remember(folders, resolvedPaths) {
        DuplicateFolderPathResolver.apply(folders, resolvedPaths)
    }
    val tree = remember(options, sortMode, descending) {
        DuplicateFolderTree.sort(DuplicateFolderTree.build(options), sortMode, descending)
    }
    val visible = remember(tree, expanded, filter) {
        DuplicateFolderTree.visible(tree, expanded, filter)
    }
    val matchingKeys = remember(tree, filter) {
        DuplicateFolderTree.matchingKeys(tree, filter)
    }
    val selection = remember(selected, options) {
        DuplicateFolderScope.selectionSummary(options, selected)
    }
    val folderCount = remember(options) {
        DuplicateFolderScope.selectionSummary(options, options.mapTo(mutableSetOf()) { it.key }).folderCount
    }
    BackHandler { if (choosing) choosing = false else onDismiss() }
    val countLabel = "${selection.folderCount} ${DuplicateFolderScope.folderWord(selection.folderCount)}"
    val sizeLabel = selection.sizeBytes?.let {
        android.text.format.Formatter.formatShortFileSize(context, it)
    } ?: "размер неизвестен"
    // Render this screen inside the Activity NavHost viewport. Its parent
    // Scaffold has ALREADY reserved status/navigation bars (safeDrawing).
    // Never create a second Dialog window or apply navigationBarsPadding:
    // dialog window insets were zero on the reported Xiaomi Android 16.
    Surface(Modifier.fillMaxSize().testTag("scope-route-screen"),
        color = MaterialTheme.colorScheme.background) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    if (choosing) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 3.dp,
                            shadowElevation = 8.dp,
                        ) {
                            // Footer stays within the Activity's already-safe
                            // viewport. Only the IME needs extra space here.
                            Column(
                                Modifier.fillMaxWidth()
                                    .imePadding()
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                    .testTag("scope-fixed-footer"),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("Выбрано: $countLabel, ${selection.fileCount} ${DuplicateFolderScope.fileWord(selection.fileCount)}, $sizeLabel",
                                    style = MaterialTheme.typography.bodyMedium)
                                Button({ onStart(options.map { it.key }.filterTo(mutableSetOf()) { it in selected }) },
                                    enabled = selection.fileCount > 0,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                        .testTag("scope-start-selected")) {
                                    Text("Начать поиск")
                                }
                            }
                        }
                    }
                },
            ) { layoutPadding ->
                Column(
                    Modifier.fillMaxSize()
                        .padding(layoutPadding)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ if (choosing) choosing = false else onDismiss() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад")
                    }
                    Text(if (choosing) "Выбор папок" else "Поиск дубликатов",
                        Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                }
                if (!choosing) {
                    Text("Область поиска: $mediaLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Проверить всё", style = MaterialTheme.typography.titleMedium)
                            Text("Все доступные файлы раздела: ${rows.count(DuplicateFolderScope::eligible)}",
                                style = MaterialTheme.typography.bodySmall)
                            Button({ onStart(null) }, Modifier.fillMaxWidth().testTag("scope-all")) {
                                Text("Проверить всё")
                            }
                        }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Проверить выборочно", style = MaterialTheme.typography.titleMedium)
                            Text("Выбор отдельных папок без полного анализа медиатеки.",
                                style = MaterialTheme.typography.bodySmall)
                            OutlinedButton({ choosing = true }, Modifier.fillMaxWidth().testTag("scope-select-folders"),
                                enabled = folders.isNotEmpty()) {
                                Icon(Icons.Rounded.FolderOpen, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Выбрать папки")
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                } else {
                    OutlinedTextField(
                        value = filter, onValueChange = { filter = it },
                        label = { Text("Название или путь папки") },
                        modifier = Modifier.fillMaxWidth().testTag("scope-search-folder"),
                        singleLine = true,
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Папки: $folderCount", modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall)
                        TextButton({
                            selected = if (matchingKeys.isNotEmpty() && matchingKeys.all { it in selected })
                                selected - matchingKeys else selected + matchingKeys
                        }, enabled = matchingKeys.isNotEmpty()) {
                            Icon(Icons.Rounded.SelectAll, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (matchingKeys.isNotEmpty() && matchingKeys.all { it in selected })
                                "Снять выбор" else "Выбрать все")
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(sortMode == DuplicateFolderSort.FILE_COUNT,
                            { sortMode = DuplicateFolderSort.FILE_COUNT },
                            label = { Text("Файлы") })
                        FilterChip(sortMode == DuplicateFolderSort.NAME,
                            { sortMode = DuplicateFolderSort.NAME; descending = false },
                            label = { Text("Название") })
                        FilterChip(sortMode == DuplicateFolderSort.SIZE,
                            { sortMode = DuplicateFolderSort.SIZE; descending = true },
                            label = { Text("Размер") })
                        TextButton({ descending = !descending }, modifier = Modifier.testTag("scope-sort-direction")) {
                            Text(if (descending) "↓" else "↑")
                        }
                    }
                    if (resolvingPaths) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("scope-folder-list"),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(visible, key = { it.node.id }) { row ->
                            val folder = row.node
                            val selectedChildren = folder.allKeys.count { it in selected }
                            val state = when {
                                selectedChildren == 0 -> ToggleableState.Off
                                selectedChildren == folder.allKeys.size -> ToggleableState.On
                                else -> ToggleableState.Indeterminate
                            }
                            val toggleSelection: () -> Unit = {
                                selected = if (state == ToggleableState.On) selected - folder.allKeys
                                    else selected + folder.allKeys
                            }
                            Row(Modifier.fillMaxWidth().padding(start = (row.depth.coerceAtMost(5) * 12).dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (folder.children.isNotEmpty()) {
                                    IconButton({ expanded = if (folder.id in expanded) expanded - folder.id
                                        else expanded + folder.id }, Modifier.size(40.dp)) {
                                        Icon(if (folder.id in expanded || filter.isNotBlank())
                                            Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                                            "Раскрыть папку")
                                    }
                                } else Spacer(Modifier.width(40.dp))
                                Icon(Icons.Rounded.Folder, null, Modifier.size(20.dp)
                                    .padding(end = 3.dp),tint=MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f).clickable {
                                    if (folder.children.isNotEmpty()) expanded =
                                        if (folder.id in expanded) expanded - folder.id else expanded + folder.id
                                    else toggleSelection()
                                }.padding(vertical = 8.dp)) {
                                    Text(folder.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium)
                                    val verifiedPath = if (folder.id.startsWith("volume:") ||
                                        folder.id.startsWith("file|")) folder.location else null
                                    Text(verifiedPath?.let { "Расположение: $it" }
                                            ?: "Расположение не определено",
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    val sizeText = folder.sizeBytes?.let {
                                        android.text.format.Formatter.formatShortFileSize(context, it)
                                    } ?: "размер неизвестен"
                                    val details = "${folder.fileCount} ${DuplicateFolderScope.fileWord(folder.fileCount)}" +
                                        (if (folder.children.isNotEmpty()) ", вложенных папок: ${folder.folderCount}" else "") +
                                        ", $sizeText"
                                    Text(details, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("scope-row-details-${folder.id}"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TriStateCheckbox(state = state, onClick = toggleSelection,
                                    modifier = Modifier.testTag("scope-node-${folder.id}"))
                            }
                            HorizontalDivider()
                        }
                    }
                }
                }
            }
    }
}

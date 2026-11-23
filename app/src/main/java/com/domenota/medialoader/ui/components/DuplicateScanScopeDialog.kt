package com.domenota.medialoader.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.library.DuplicateFolderScope

/** One full-screen picker shared by Music and Gallery.
 * Listing folders reads cached metadata only, never decodes or hashes media.
 * Header and launch actions remain above system navigation insets.
 */
@Composable
internal fun DuplicateScanScopeDialog(
    rows: List<DownloadEntity>,
    mediaLabel: String,
    onDismiss: () -> Unit,
    onStart: (Set<String>?) -> Unit,
) {
    val folders = remember(rows) { DuplicateFolderScope.options(rows) }
    var choosing by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val visible = remember(folders, filter) {
        folders.filter { option ->
            filter.isBlank() || option.name.contains(filter, ignoreCase = true) ||
                option.location.contains(filter, ignoreCase = true)
        }
    }
    val selectedCount = remember(selected, folders) {
        folders.filter { it.key in selected }.sumOf { it.fileCount }
    }
    BackHandler { if (choosing) choosing = false else onDismiss() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(WindowInsets.safeDrawing.asPaddingValues())
                .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ if (choosing) choosing = false else onDismiss() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад")
                    }
                    Text(if (choosing) "Выбор папок" else "Поиск дубликатов",
                        Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                }
                if (!choosing) {
                    Text("Область поиска · $mediaLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Проверить всё", style = MaterialTheme.typography.titleMedium)
                            Text("Все доступные файлы раздела · ${rows.count(DuplicateFolderScope::eligible)}",
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
                        label = { Text("Поиск папки") },
                        modifier = Modifier.fillMaxWidth().testTag("scope-search-folder"),
                        singleLine = true,
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Папки: ${folders.size}", modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall)
                        TextButton({
                            selected = if (visible.isNotEmpty() && visible.all { it.key in selected })
                                selected - visible.map { it.key }.toSet()
                            else selected + visible.map { it.key }
                        }, enabled = visible.isNotEmpty()) {
                            Icon(Icons.Rounded.SelectAll, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (visible.isNotEmpty() && visible.all { it.key in selected })
                                "Снять выбор" else "Выбрать все")
                        }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("scope-folder-list"),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(visible, key = { it.key }) { folder ->
                            val checked = folder.key in selected
                            Row(Modifier.fillMaxWidth().clickable {
                                selected = if (checked) selected - folder.key else selected + folder.key
                            }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked, onCheckedChange = { value ->
                                    selected = if (value) selected + folder.key else selected - folder.key
                                })
                                Icon(Icons.Rounded.Folder, null, modifier = Modifier.padding(end = 8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium)
                                    Text(folder.location, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(folder.fileCount.toString(), style = MaterialTheme.typography.labelMedium)
                            }
                            HorizontalDivider()
                        }
                    }
                    HorizontalDivider()
                    Text("Выбрано: ${selected.size} папок · $selectedCount файлов",
                        style = MaterialTheme.typography.bodyMedium)
                    Button({ onStart(selected.toSet()) },
                        enabled = selectedCount > 0,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("scope-start-selected")) {
                        Text("Начать поиск")
                    }
                }
            }
        }
    }
}

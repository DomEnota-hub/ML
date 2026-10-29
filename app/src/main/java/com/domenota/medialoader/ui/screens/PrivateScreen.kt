package com.domenota.medialoader.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.runtime.saveable.rememberSaveable
import com.domenota.medialoader.core.database.CatalogFolderEntity
import com.domenota.medialoader.core.database.CatalogAssignmentEntity
import com.domenota.medialoader.data.catalog.runFileBatch
import com.domenota.medialoader.ui.components.DownloadGroupCard
import com.domenota.medialoader.ui.model.DownloadUiItem
import com.domenota.medialoader.ui.model.VkDownloadGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.privatevault.PrivateAccessManager
import com.domenota.medialoader.data.privatevault.PrivateAccessMode
import com.domenota.medialoader.ui.components.DownloadRow
import com.domenota.medialoader.ui.components.PrivateAccessGate
import com.domenota.medialoader.ui.components.PrivateProtectionSettings
import com.domenota.medialoader.ui.model.toUi
import kotlinx.coroutines.launch

@Composable
fun PrivateScreen(
    access: PrivateAccessManager,
    items: List<DownloadEntity>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onRestore: suspend (String) -> Unit,
    onFinishMove: suspend (String) -> Unit,
    onDelete: suspend (String) -> Unit,
    onRename: (String, String) -> Unit,
    externalBusy: Boolean = false,
    catalogFolders: List<CatalogFolderEntity> = emptyList(),
    catalogAssignments: List<CatalogAssignmentEntity> = emptyList(),
    onSetFolder: suspend (String, String?) -> Unit = { _, _ -> },
    onSetFavorite: suspend (String, Boolean) -> Unit = { _, _ -> },
    onCreateFolder: suspend (String) -> String = { "" },
    onOpenGrid: ((String, List<String>) -> Unit)? = null,
) {
    val state by access.state.collectAsState()
    var settings by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<List<String>?>(null) }
    var folderTarget by remember { mutableStateOf<List<String>?>(null) }
    var autoAuthenticate by rememberSaveable { mutableStateOf(true) }
    var expandedGroups by rememberSaveable { mutableStateOf(setOf<String>()) }
    var groupLimits by rememberSaveable { mutableStateOf(mapOf<String, Int>()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("ui", 0) }
    var gridMode by rememberSaveable { mutableStateOf(prefs.getBoolean("private_grid", false)) }
    val uiItems = remember(items) { items.map { it.toUi() } }
    val groups = remember(uiItems) { uiItems.groupBy { VkDownloadGroup.key(it) } }
    val assignments = remember(catalogAssignments) { catalogAssignments.associateBy { it.downloadId } }
    fun action(block: suspend () -> Unit) {
        if (busy || externalBusy) return
        busy = true
        scope.launch {
            try { block() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { Toast.makeText(context, error.message ?: "Не удалось выполнить действие", Toast.LENGTH_LONG).show() }
            finally { busy = false }
        }
    }
    suspend fun batch(ids: List<String>, block: suspend (String) -> Unit) {
        val result = runFileBatch(ids, block)
        Toast.makeText(context, result.summary, Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(state.unlocked) {
        if (!state.unlocked) { deleteTarget = null; folderTarget = null }
    }
    LaunchedEffect(state.mode, state.unlocked) {
        if (state.mode == PrivateAccessMode.NONE && !state.unlocked) access.unlockWithoutProtection()
    }
    BackHandler(busy || externalBusy || settings) {
        if (!busy && !externalBusy) settings = false
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (settings) settings = false else onBack() }, enabled = !busy && !externalBusy) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад")
            }
            Text(if (settings) "Защита личного" else "Личное", style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f))
            if (state.unlocked && !settings) {
                IconButton(onClick = {
                    gridMode = !gridMode
                    prefs.edit().putBoolean("private_grid", gridMode).apply()
                }, enabled = !busy && !externalBusy) {
                    Icon(if (gridMode) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                        if (gridMode) "Показать списком" else "Показать сеткой")
                }
                IconButton(onClick = { settings = true; autoAuthenticate = true; access.lock() }, enabled = !busy && !externalBusy) {
                    Icon(Icons.Rounded.Settings, "Настройки личного")
                }
                if (state.mode != PrivateAccessMode.NONE) IconButton(onClick = { autoAuthenticate = false; access.lock() }, enabled = !busy && !externalBusy) {
                    Icon(Icons.Rounded.Lock, "Заблокировать личное")
                }
            }
        }
        when {
            externalBusy && state.mode != PrivateAccessMode.UNCONFIGURED -> Column(
                Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
                Text("Перемещаем файлы…", Modifier.padding(16.dp))
            }
            !state.unlocked -> PrivateAccessGate(access, Modifier.weight(1f), autoAuthenticate = autoAuthenticate)
            settings -> PrivateProtectionSettings(access, onDone = { settings = false })
            else -> Box(Modifier.weight(1f).fillMaxWidth()) {
                @Composable fun fileRow(row: DownloadUiItem, modifier: Modifier = Modifier) {
                    val favorite = assignments[row.id]?.isFavorite == true
                    DownloadRow(row, modifier = modifier, compact = gridMode,
                        onOpen = { if (!busy && !externalBusy) {
                            if (gridMode && onOpenGrid != null) onOpenGrid(row.id, uiItems.map { it.id })
                            else onOpen(row.id)
                        } }, onCancel = {},
                        onHide = { if (!busy && !externalBusy) deleteTarget = listOf(row.id) },
                        onRename = { if (!busy && !externalBusy) onRename(row.id, it) },
                        onRestoreFromPrivate = { action { batch(listOf(row.id), onRestore) } },
                        favorite = favorite,
                        folderLabel = catalogFolders.find { it.id == assignments[row.id]?.folderId }?.name,
                        onToggleFavorite = { action { batch(listOf(row.id)) { onSetFavorite(it, !favorite) } } },
                        onChooseFolder = { if (!busy && !externalBusy) folderTarget = listOf(row.id) },
                        allowSharing = false, privateDeletion = true)
                }
                LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (items.isEmpty()) item { Text("Здесь пока нет файлов. Переместите их через меню в загрузках.") }
                    items(items.filter { it.privateSourceUri != null }, key = { "pending:${it.id}" }) { row ->
                        Column {
                            Text("Публичная копия ещё не удалена и может быть видна в галерее.", color = MaterialTheme.colorScheme.error)
                            OutlinedButton(onClick = { action { onFinishMove(row.id) } }, enabled = !busy && !externalBusy) {
                                Text("Завершить перенос")
                            }
                        }
                    }
                    if (gridMode) {
                        items(uiItems.chunked(4), key = { "grid:${it.first().id}" }) { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                row.forEach { entry -> key(entry.id) { fileRow(entry, Modifier.weight(1f)) } }
                                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    } else groups.forEach { (groupKey, group) ->
                        if (group.size == 1) item(key = "file:${group.single().id}") { fileRow(group.single()) }
                        else {
                            val expanded = groupKey in expandedGroups
                            val limit = groupLimits[groupKey] ?: 24
                            val ids = group.map { it.id }
                            val allFavorite = group.all { assignments[it.id]?.isFavorite == true }
                            item(key = "group:$groupKey") {
                                DownloadGroupCard(group, expanded,
                                    onExpand = { expandedGroups = if (expanded) expandedGroups - groupKey else expandedGroups + groupKey },
                                    onOpen = { if (!busy && !externalBusy) onOpen(it) },
                                    allFavorite = allFavorite,
                                    onFavorite = { action { batch(ids) { onSetFavorite(it, !allFavorite) } } },
                                    onChooseFolder = { folderTarget = ids },
                                    onRestore = { action { batch(ids, onRestore) } },
                                    onDelete = { deleteTarget = ids }, privateGroup = true,
                                    actionsEnabled = !busy && !externalBusy)
                            }
                            if (expanded) {
                                items(group.take(limit), key = { "child:${it.id}" }) { fileRow(it) }
                                if (limit < group.size) item(key = "more:$groupKey") {
                                    TextButton(onClick = { groupLimits = groupLimits + (groupKey to limit + 24) }) {
                                        Text("Показать ещё ${minOf(24, group.size - limit)}")
                                    }
                                }
                            }
                        }
                    }
                }
                if (busy || externalBusy) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background.copy(alpha = 0.93f)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                        Text("Перемещаем файл…", Modifier.padding(16.dp))
                    }
                }
            }
        }
    }
    if (state.unlocked && !settings && folderTarget != null) DownloadFolderSheet(
        folders = catalogFolders, assignments = assignments, items = uiItems,
        selectedKey = CATALOG_ALL, targetDownloadId = folderTarget!!.first(),
        onDismiss = { folderTarget = null }, onSelect = {}, onCreate = onCreateFolder,
        onRename = { _, _ -> }, onDelete = {},
        onAssign = { _, folderId ->
            if (!busy && !externalBusy && state.unlocked) {
                busy = true
                try { batch(folderTarget.orEmpty()) { onSetFolder(it, folderId) } }
                finally { busy = false }
            }
        })
    if (state.unlocked && deleteTarget != null) AlertDialog(
        onDismissRequest = { deleteTarget = null }, title = { Text("Удалить личные файлы (${deleteTarget!!.size})?") },
        text = { Text("Файлы (${deleteTarget!!.size}) будут удалены навсегда. Вернуть их из корзины не получится.") },
        confirmButton = { TextButton(onClick = { val ids = deleteTarget!!; deleteTarget = null; action { batch(ids, onDelete) } }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Отмена") } })
}

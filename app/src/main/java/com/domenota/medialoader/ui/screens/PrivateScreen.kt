package com.domenota.medialoader.ui.screens

import com.domenota.medialoader.ui.components.AppNotice
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.domenota.medialoader.data.privatevault.VaultOperations

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
    onOpenVault: (() -> Unit)? = null,
    legacyRecoveryProbe: (suspend () -> Boolean)? = null,
) {
    val state by access.state.collectAsState()
    var settings by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<List<String>?>(null) }
    var folderTarget by remember { mutableStateOf<List<String>?>(null) }
    var restoreTarget by remember { mutableStateOf<List<String>?>(null) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selecting by remember { mutableStateOf(false) }
    var expandedGroups by rememberSaveable { mutableStateOf(setOf<String>()) }
    var groupLimits by rememberSaveable { mutableStateOf(mapOf<String, Int>()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("ui", 0) }
    // No permanent legacy entry on ordinary installations. Probe only after authorization,
    // off the main thread, and retain recovery access on an unreadable or failed index.
    var legacyRecoveryAvailable by remember(state.unlocked) { mutableStateOf(false) }
    LaunchedEffect(state.unlocked, onOpenVault != null, legacyRecoveryProbe) {
        if (!state.unlocked || onOpenVault == null) return@LaunchedEffect
        val required = try {
            withContext(Dispatchers.IO) {
                access.requireUnlocked()
                legacyRecoveryProbe?.invoke() ?: VaultOperations(context).snapshot().let {
                    it.items.isNotEmpty() || it.unreadableRecords > 0
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { true }
        if (access.state.value.unlocked) legacyRecoveryAvailable = required
    }
    var gridMode by rememberSaveable { mutableStateOf(prefs.getBoolean("private_grid", false)) }
    var mediaType by rememberSaveable { mutableStateOf<com.domenota.medialoader.core.model.MediaType?>(null) }
    val visibleItems=remember(items,mediaType){
        com.domenota.medialoader.ui.navigation.filterLibraryMedia(items,mediaType){it.mediaType}
    }
    val visibleIds=remember(visibleItems){visibleItems.mapTo(mutableSetOf()){it.id}}
    val uiItems = remember(visibleItems) { visibleItems.map { it.toUi() } }
    val groups = remember(uiItems) { uiItems.groupBy { VkDownloadGroup.key(it) } }
    val assignments = remember(catalogAssignments) { catalogAssignments.associateBy { it.downloadId } }
    fun action(block: suspend () -> Unit) {
        if (busy || externalBusy) return
        busy = true
        scope.launch {
            try { block() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { AppNotice.post(context, "Не удалось выполнить действие") }
            finally { busy = false }
        }
    }
    suspend fun batch(ids: List<String>, block: suspend (String) -> Unit) {
        val result = runFileBatch(ids, block)
        AppNotice.postBatch(context, "Результат операции", result, "Обработано")
    }
    LaunchedEffect(state.unlocked,visibleIds) {
        if (!state.unlocked) {
            deleteTarget = null; folderTarget = null; restoreTarget = null
            selectedIds = emptySet(); selecting = false
        } else {
            selectedIds = selectedIds.intersect(visibleIds)
        }
    }
    LaunchedEffect(state.mode, state.unlocked) {
        if (state.mode == PrivateAccessMode.NONE && !state.unlocked) access.unlockWithoutProtection()
    }
    BackHandler(busy || externalBusy || settings || selecting) {
        if (!busy && !externalBusy) {
            if (selecting) { selecting = false; selectedIds = emptySet() }
            else settings = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (settings) settings = false else onBack() }, enabled = !busy && !externalBusy) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад")
            }
            Text(if (settings) "Защита личного" else if (selecting) "Выбрано: ${selectedIds.size}" else "Личное",
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (selecting && state.unlocked) {
                TextButton(onClick = { selectedIds = if (selectedIds==visibleIds) emptySet()
                    else visibleIds },enabled = !busy && !externalBusy) {
                    Text(if (selectedIds==visibleIds) "Снять всё" else "Выбрать все")
                }
                IconButton(onClick = { selecting = false; selectedIds = emptySet() },
                    enabled = !busy && !externalBusy) {
                    Icon(Icons.Rounded.Close,"Отменить выбор")
                }
            }
            if (state.unlocked && !settings && !selecting) {
                if (legacyRecoveryAvailable && onOpenVault != null) {
                    TextButton(onClick = { if (access.state.value.unlocked) onOpenVault() },
                        enabled = !busy && !externalBusy) { Text("Ранее защищённые файлы") }
                }
                IconButton(onClick = {
                    gridMode = !gridMode
                    prefs.edit().putBoolean("private_grid", gridMode).apply()
                }, enabled = !busy && !externalBusy) {
                    Icon(if (gridMode) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                        if (gridMode) "Показать списком" else "Показать сеткой")
                }
                IconButton(onClick = { settings = true; access.lock() }, enabled = !busy && !externalBusy) {
                    Icon(Icons.Rounded.Settings, "Настройки личного")
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
            !state.unlocked -> PrivateAccessGate(access, Modifier.weight(1f))
            settings -> PrivateProtectionSettings(access, onDone = { settings = false })
            else -> Box(Modifier.weight(1f).fillMaxWidth()) {
                @Composable fun fileRow(row: DownloadUiItem, modifier: Modifier = Modifier) {
                    val favorite = assignments[row.id]?.isFavorite == true
                    var swipeDistance by remember(row.id) { mutableFloatStateOf(0f) }
                    val swipeGesture = Modifier.pointerInput(row.id, state.unlocked, busy, externalBusy, selecting) {
                        if (!state.unlocked || busy || externalBusy || selecting) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragStart = { swipeDistance = 0f },
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                swipeDistance += amount
                            },
                            onDragEnd = {
                                // Reveal confirmation; never publish a private file on a gesture alone.
                                if (swipeDistance < -72f && !busy && !externalBusy && state.unlocked)
                                    restoreTarget = listOf(row.id)
                                swipeDistance = 0f
                            },
                            onDragCancel = { swipeDistance = 0f }
                        )
                    }
                    Box(modifier.then(swipeGesture)) {
                        DownloadRow(row, modifier = Modifier.fillMaxWidth(), compact = gridMode,
                            onOpen = { if (!busy && !externalBusy) {
                                if (selecting) selectedIds = if (row.id in selectedIds)
                                    selectedIds - row.id else selectedIds + row.id
                                else if (onOpenGrid != null) onOpenGrid(row.id, uiItems.map { it.id })
                                else onOpen(row.id)
                            } }, onCancel = {},
                            onLongSelect = { if (state.unlocked && !busy && !externalBusy) {
                                selecting = true; selectedIds = selectedIds + row.id
                            } },
                            selectionMode = selecting, selected = row.id in selectedIds,
                            onHide = { if (!busy && !externalBusy) deleteTarget = listOf(row.id) },
                            onRename = { if (!busy && !externalBusy) onRename(row.id, it) },
                            onRestoreFromPrivate = { if (!busy && !externalBusy) restoreTarget = listOf(row.id) },
                            favorite = favorite,
                            folderLabel = catalogFolders.find { it.id == assignments[row.id]?.folderId }?.name,
                            onToggleFavorite = { action { batch(listOf(row.id)) { onSetFavorite(it, !favorite) } } },
                            onChooseFolder = { if (!busy && !externalBusy) folderTarget = listOf(row.id) },
                            allowSharing = false, privateDeletion = true)
                    }
                }
                Column {
                com.domenota.medialoader.ui.components.LibraryMediaTypeFilters(mediaType,{next->
                    mediaType=next;selectedIds=emptySet();selecting=false
                },enabled=!busy&&!externalBusy&&!selecting)
                if (selecting) {
                    Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.medium,
                        modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment=Alignment.CenterVertically,
                            horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.CheckCircle,null,tint=MaterialTheme.colorScheme.primary)
                            Text("${selectedIds.size} файлов",Modifier.weight(1f))
                            Button(onClick={restoreTarget=selectedIds.intersect(visibleIds).toList()},
                                enabled=selectedIds.isNotEmpty() && !busy && !externalBusy) {
                                Icon(Icons.Rounded.Restore,null)
                                Spacer(Modifier.width(6.dp))
                                Text("Вынести")
                            }
                        }
                    }
                    Text("Долгое нажатие: выбор. Свайп влево: вынести с подтверждением.",
                        Modifier.padding(horizontal=16.dp,vertical=6.dp),
                        style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (visibleItems.isEmpty()) item { Text(if(items.isEmpty()) "Здесь пока нет файлов. Переместите их через меню в загрузках." else "Нет файлов этого типа") }
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
                                    onOpen = { if (!busy && !externalBusy) {
                                        if (onOpenGrid != null) onOpenGrid(it, group.map { row -> row.id }) else onOpen(it)
                                    } },
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
        folders = catalogFolders, assignments = assignments, items = items.map{it.toUi()},
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
    if (state.unlocked && !settings && restoreTarget != null) AlertDialog(
        onDismissRequest = { if (!busy && !externalBusy) restoreTarget = null },
        title = { Text("Вынести из Личного?") },
        text = { Text("Выбранные файлы (${restoreTarget!!.size}) станут доступны в обычной медиатеке и системной галерее. Исходная приватная копия сохраняется при ошибке восстановления.") },
        confirmButton = { TextButton(onClick = {
            val ids=restoreTarget.orEmpty()
            restoreTarget=null
            if (ids.isNotEmpty() && state.unlocked) action {
                batch(ids) { id -> access.requireUnlocked(); onRestore(id) }
                selectedIds=emptySet(); selecting=false
            }
        }, enabled = !busy && !externalBusy) { Text("Вынести") } },
        dismissButton = { TextButton(onClick = { restoreTarget = null }) { Text("Отмена") } })
    if (state.unlocked && deleteTarget != null) AlertDialog(
        onDismissRequest = { deleteTarget = null },
        title = { Text("Удалить личные файлы (${deleteTarget!!.size})?") },
        text = { Text("Файлы (${deleteTarget!!.size}) будут удалены навсегда. Вернуть их из корзины не получится.") },
        confirmButton = { TextButton(onClick = {
            val ids = deleteTarget!!
            deleteTarget = null
            action { batch(ids, onDelete) }
        }) { Text("Удалить навсегда", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Отмена") } })
}

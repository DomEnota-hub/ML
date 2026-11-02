package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.database.CatalogAssignmentEntity
import com.domenota.medialoader.core.database.CatalogFolderEntity
import com.domenota.medialoader.ui.model.DownloadUiItem
import com.domenota.medialoader.ui.model.DownloadUiState
import com.domenota.medialoader.ui.model.DownloadCatalogSelection
import kotlinx.coroutines.launch

internal const val CATALOG_ALL = DownloadCatalogSelection.ALL
internal const val CATALOG_FAVORITES = DownloadCatalogSelection.FAVORITES
internal const val CATALOG_UNFILED = DownloadCatalogSelection.UNFILED
internal fun folderKey(id: String) = DownloadCatalogSelection.folder(id)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadFolderSheet(
    folders: List<CatalogFolderEntity>,
    assignments: Map<String, CatalogAssignmentEntity>,
    items: List<DownloadUiItem>,
    selectedKey: String,
    targetDownloadId: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onCreate: suspend (String) -> String,
    onRename: suspend (String, String) -> Unit,
    onDelete: suspend (String) -> Unit,
    onAssign: suspend (String, String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var editTarget by remember { mutableStateOf<CatalogFolderEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<CatalogFolderEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val completed = items.filter { it.state == DownloadUiState.COMPLETED }
    val currentFolderId = targetDownloadId?.let { assignments[it]?.folderId }
    val favoriteCount = completed.count { assignments[it.id]?.isFavorite == true }
    val unfiledCount = completed.count { assignments[it.id]?.folderId == null }

    fun choose(key: String, folderId: String?) {
        if (targetDownloadId == null) { onSelect(key); onDismiss() }
        else scope.launch {
            busy = true
            runCatching { onAssign(targetDownloadId, folderId) }
                .onSuccess { onDismiss() }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; error = it.message ?: "Не удалось назначить папку" }
            busy = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        com.domenota.medialoader.ui.theme.TransparentWindowNavigation()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (targetDownloadId == null) "Папки" else "Выбрать папку",
                style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            if (targetDownloadId == null) {
                CatalogSheetRow(Icons.Rounded.FolderOpen, "Все файлы", completed.size,
                    selectedKey == CATALOG_ALL, !busy) { choose(CATALOG_ALL, null) }
                CatalogSheetRow(Icons.Rounded.Star, "Избранное", favoriteCount,
                    selectedKey == CATALOG_FAVORITES, !busy) { choose(CATALOG_FAVORITES, null) }
            }
            CatalogSheetRow(Icons.Rounded.FolderOpen, "Без папки", unfiledCount,
                if (targetDownloadId == null) selectedKey == CATALOG_UNFILED
                    else currentFolderId == null, !busy) {
                choose(CATALOG_UNFILED, null)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (targetDownloadId == null) {
                androidx.compose.material3.Button(
                    onClick = { creating = true; name = ""; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Text("Новая папка", modifier = Modifier.padding(start = 8.dp))
                }
                Spacer(Modifier.height(6.dp))
            }
            Text("Мои папки", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (folders.isEmpty()) Text("Пока нет папок", modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (targetDownloadId != null) {
                androidx.compose.material3.OutlinedButton(
                    onClick = { creating = true; name = ""; error = null },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Text("Новая папка", modifier = Modifier.padding(start = 8.dp))
                }
            }
            folders.forEach { folder ->
                val count = completed.count { assignments[it.id]?.folderId == folder.id }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CatalogSheetRow(Icons.Rounded.Folder, folder.name, count,
                        if (targetDownloadId == null) selectedKey == folderKey(folder.id)
                            else currentFolderId == folder.id,
                        !busy, Modifier.weight(1f)) { choose(folderKey(folder.id), folder.id) }
                    if (targetDownloadId == null) {
                        var menuOpen by remember { mutableStateOf(false) }
                        androidx.compose.foundation.layout.Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "Действия с папкой ${folder.name}")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text("Переименовать") }, onClick = {
                                    menuOpen = false; editTarget = folder; name = folder.name; error = null
                                })
                                DropdownMenuItem(text = { Text("Удалить папку") }, onClick = {
                                    menuOpen = false; deleteTarget = folder; error = null
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating || editTarget != null) AlertDialog(
        onDismissRequest = { if (!busy) { creating = false; editTarget = null } },
        title = { Text(if (creating) "Новая папка" else "Переименовать папку") },
        text = {
            Column {
                OutlinedTextField(name, { name = it; error = null },
                    label = { Text("Название") }, singleLine = true,
                    isError = error != null, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = !busy && name.isNotBlank(), onClick = {
            busy = true
            scope.launch {
                runCatching {
                    val target = editTarget
                    if (target == null) onCreate(name) else { onRename(target.id, name); target.id }
                }.onSuccess { id ->
                    val wasCreating = creating
                    creating = false; editTarget = null
                    if (wasCreating) {
                        if (targetDownloadId != null) choose(folderKey(id), id)
                        else { onSelect(folderKey(id)); onDismiss() }
                    }
                }.onFailure { error = it.message ?: "Не удалось сохранить папку" }
                busy = false
            }
        }) { Text("Сохранить") } },
        dismissButton = { TextButton(enabled = !busy, onClick = {
            creating = false; editTarget = null
        }) { Text("Отмена") } },
    )

    deleteTarget?.let { folder -> AlertDialog(
        onDismissRequest = { if (!busy) deleteTarget = null },
        title = { Text("Удалить папку «${folder.name}»?") },
        text = { Column {
            Text("Файлы останутся на телефоне и будут доступны в «Все файлы».")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                runCatching { onDelete(folder.id) }
                    .onSuccess {
                        deleteTarget = null
                        if (selectedKey == folderKey(folder.id)) onSelect(CATALOG_ALL)
                    }
                    .onFailure { error = it.message ?: "Не удалось удалить папку" }
                busy = false
            }
        }) { Text("Удалить") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { deleteTarget = null }) {
            Text("Отмена")
        } },
    ) }
}

@Composable
private fun CatalogSheetRow(
    icon: ImageVector,
    label: String,
    count: Int,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(modifier.fillMaxWidth().background(
        if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
        .clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Text(count.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

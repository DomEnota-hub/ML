package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import com.domenota.medialoader.ui.components.NoticeSheet as ModalBottomSheet
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
import com.domenota.medialoader.ui.components.AppNotice
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal const val CATALOG_ALL = DownloadCatalogSelection.ALL
internal const val CATALOG_FAVORITES = DownloadCatalogSelection.FAVORITES
internal const val CATALOG_UNFILED = DownloadCatalogSelection.UNFILED
internal fun folderKey(id: String) = DownloadCatalogSelection.folder(id)

/** Externally imported Gallery items are not part of the download history. */
internal fun visibleDownloadFolders(
    folders: List<CatalogFolderEntity>,
    items: List<DownloadUiItem>,
    assignments: Map<String, CatalogAssignmentEntity>,
    galleryOnlyFolderIds: Set<String>,
    assigning: Boolean,
): List<CatalogFolderEntity> {
    if (assigning) return folders
    val withDownloads = items.asSequence()
        .filter { it.state == DownloadUiState.COMPLETED }
        .mapNotNull { assignments[it.id]?.folderId }
        .toSet()
    return folders.filter { it.id !in galleryOnlyFolderIds || it.id in withDownloads }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadFolderSheet(
    folders: List<CatalogFolderEntity>,
    assignments: Map<String, CatalogAssignmentEntity>,
    items: List<DownloadUiItem>,
    galleryOnlyFolderIds: Set<String> = emptySet(),
    selectedKey: String,
    targetDownloadId: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onCreate: suspend (String) -> String,
    onRename: suspend (String, String) -> Unit,
    onDelete: suspend (String) -> Unit,
    onAssign: suspend (String, String?) -> Unit,
    hiddenFolderIds: Set<String> = emptySet(),
    onFolderVisibility: (suspend (String, Boolean) -> Unit)? = null,
    pendingVisibilityFolderId: String? = null,
    nomediaFolderStatuses: Map<String,String> = emptyMap(),
    onCheckFolderAccess: (suspend (String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var editTarget by remember { mutableStateOf<CatalogFolderEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<CatalogFolderEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val completed = items.filter { it.state == DownloadUiState.COMPLETED }
    val shownFolders = visibleDownloadFolders(
        folders, items, assignments, galleryOnlyFolderIds, assigning = targetDownloadId != null)
    val hiddenGalleryFolders = folders.size - shownFolders.size
    val currentFolderId = targetDownloadId?.let { assignments[it]?.folderId }
    val general = completed.filter { DownloadCatalogSelection.includes(CATALOG_ALL, assignments[it.id], hiddenFolderIds) }
    val favoriteCount = general.count { assignments[it.id]?.isFavorite == true }
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

    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        com.domenota.medialoader.ui.theme.TransparentWindowNavigation()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .navigationBarsPadding().imePadding()
            .padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (targetDownloadId == null) "Папки" else "Выбрать папку",
                style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            if (targetDownloadId == null) {
                CatalogSheetRow(Icons.Rounded.FolderOpen, "Все файлы", general.size,
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
            if (shownFolders.isEmpty()) Text("Пока нет папок загрузок", modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (hiddenGalleryFolders > 0 && targetDownloadId == null) {
                Text("Папки с файлами только из галереи доступны в разделе «Галерея».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (targetDownloadId != null) {
                androidx.compose.material3.OutlinedButton(
                    onClick = { creating = true; name = ""; error = null },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Text("Новая папка", modifier = Modifier.padding(start = 8.dp))
                }
            }
            shownFolders.forEach { folder ->
                val count = completed.count { assignments[it.id]?.folderId == folder.id }
                val folderHidden = folder.id in hiddenFolderIds
                val visibilityPending = pendingVisibilityFolderId == folder.id
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CatalogSheetRow(if (folderHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Folder,
                        folder.name, count,
                        if (targetDownloadId == null) selectedKey == folderKey(folder.id)
                            else currentFolderId == folder.id,
                        !busy, Modifier.weight(1f), pendingVisibility = visibilityPending,
                         statusDetail = nomediaFolderStatuses[folder.id]) { choose(folderKey(folder.id), folder.id) }
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
                                if (onFolderVisibility != null) DropdownMenuItem(
                                    text = { Text(if (visibilityPending) "Старый журнал: диагностика" else if (folderHidden) "Показать в ML" else "Скрыть в ML и Android") },
                                    leadingIcon = { Icon(if (folderHidden) Icons.Rounded.RemoveRedEye else Icons.Rounded.VisibilityOff,null) },
                                    enabled = !busy && (pendingVisibilityFolderId == null || visibilityPending),
                                    onClick = {
                                        menuOpen = false; busy = true
                                        scope.launch {
                                            try { onFolderVisibility(folder.id,!folderHidden) }
                                            catch (cancelled: CancellationException) { throw cancelled }
                                            catch (e: Exception) { AppNotice.post(context,e.message ?: "Не удалось изменить видимость папки") }
                                            finally { busy = false }
                                        }
                                    })
                                if (folder.id in nomediaFolderStatuses && onCheckFolderAccess != null)
                                    DropdownMenuItem(text={Text("Проверить доступ")},enabled=!busy,onClick={
                                        menuOpen=false;busy=true
                                        scope.launch {
                                            try { onCheckFolderAccess(folder.id) }
                                            catch(cancelled:CancellationException){throw cancelled}
                                            catch(error:Exception){AppNotice.post(context,error.message ?: "Доступ не подтверждён")}
                                            finally { busy=false }
                                        }
                                    })
                                DropdownMenuItem(text = { Text("Удалить папку") }, enabled = !busy && pendingVisibilityFolderId == null, onClick = {
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
    pendingVisibility: Boolean = false,
    statusDetail: String? = null,
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
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (statusDetail != null) {
                Text(statusDetail,style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (pendingVisibility) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = null,
                        modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Перенос не завершён", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Text(count.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

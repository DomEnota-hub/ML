package com.domenota.medialoader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.storage.SavedFileStatus
import com.domenota.medialoader.ui.model.DownloadUiItem
import com.domenota.medialoader.ui.model.DownloadUiState
import com.domenota.medialoader.ui.model.VkDownloadGroup

@Composable
fun DownloadGroupCard(
    group: List<DownloadUiItem>,
    expanded: Boolean,
    onExpand: () -> Unit,
    onOpen: (String) -> Unit,
    allFavorite: Boolean = false,
    onFavorite: (() -> Unit)? = null,
    onChooseFolder: (() -> Unit)? = null,
    onMoveToPrivate: (() -> Unit)? = null,
    onRestore: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    privateGroup: Boolean = false,
    actionsEnabled: Boolean = true,
) {
    if (group.isEmpty()) return
    var menu by remember { mutableStateOf(false) }
    val cover = group.first()
    val community = VkDownloadGroup.isCommunity(cover)
    val missing = group.count { it.state == DownloadUiState.COMPLETED && it.fileStatus == SavedFileStatus.MISSING }
    val inaccessible = group.count { it.state == DownloadUiState.COMPLETED && it.fileStatus == SavedFileStatus.INACCESSIBLE }
    val completed = group.count { it.state == DownloadUiState.COMPLETED } - missing - inaccessible
    val failures = if (group.any { it.state == DownloadUiState.FAILED }) " · Есть ошибки" else ""
    val absent = if (missing > 0) " · Отсутствует: $missing" else ""
    val unavailable = if (inaccessible > 0) " · Нет доступа: $inaccessible" else ""
    AppCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(enabled = actionsEnabled, onClick = onExpand).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DownloadThumbnail(cover.savedUri, cover.previewUrl, cover.kind,
                    Modifier.size(44.dp).clickable(enabled = actionsEnabled && cover.state == DownloadUiState.COMPLETED,
                        onClick = { onOpen(cover.id) }))
                Column(Modifier.weight(1f)) {
                    Text(if (community) "VK · ${group.size} изображений" else "Публикация · ${group.size} файлов",
                        style = MaterialTheme.typography.titleMedium)
                    Text("Сохранено $completed из ${group.size}$failures$absent$unavailable",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    if (expanded) "Свернуть" else "Развернуть")
            }
            if (listOf(onFavorite, onChooseFolder, onMoveToPrivate, onRestore, onDelete).any { it != null }) Box {
                IconButton(onClick = { menu = true }, enabled = actionsEnabled) {
                    Icon(Icons.Rounded.MoreVert, "Действия с группой")
                }
                DropdownMenu(expanded = menu && actionsEnabled, onDismissRequest = { menu = false }) {
                    onFavorite?.let { action -> DropdownMenuItem(
                        text = { Text(if (allFavorite) "Убрать из избранного" else "В избранное") },
                        leadingIcon = { Icon(Icons.Rounded.Star, null) }, onClick = { menu = false; action() }) }
                    onChooseFolder?.let { action -> DropdownMenuItem(text = { Text("Добавить в папку") },
                        leadingIcon = { Icon(Icons.Rounded.Folder, null) }, onClick = { menu = false; action() }) }
                    if (!privateGroup) onMoveToPrivate?.let { action -> DropdownMenuItem(text = { Text("Переместить в личное") },
                        leadingIcon = { Icon(Icons.Rounded.Lock, null) }, onClick = { menu = false; action() }) }
                    onRestore?.let { action -> DropdownMenuItem(text = { Text("Вернуть из личного") },
                        leadingIcon = { Icon(Icons.Rounded.LockOpen, null) }, onClick = { menu = false; action() }) }
                    onDelete?.let { action -> DropdownMenuItem(
                        text = { Text(if (privateGroup) "Удалить навсегда" else "Удалить", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; action() }) }
                }
            }
        }
    }
}

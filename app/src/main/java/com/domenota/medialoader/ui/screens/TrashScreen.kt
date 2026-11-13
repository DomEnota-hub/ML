package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.domenota.medialoader.ui.components.NeutralOutlinedButton as OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.ui.components.DownloadThumbnail
import com.domenota.medialoader.ui.model.DownloadUiItem
import com.domenota.medialoader.data.library.PhoneMediaTrash

/**
 * App downloads use private trashed URI, while phone media use system MediaStore trash.
 * Selecting via a circular checkbox is independent of opening a file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    items: List<DownloadUiItem>,
    phoneItems: List<DownloadUiItem> = emptyList(),
    onRestorePhone: (Set<String>) -> Unit = {},
    onDeletePhone: (Set<String>) -> Unit = {},
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onRestore: (Set<String>) -> Unit,
    onDelete: (Set<String>) -> Unit,
) {
    var grid by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var phoneTab by remember { mutableStateOf(false) }
    val allItems = if (phoneTab) phoneItems else items
    fun phoneIds(ids: Set<String>) = ids.filterTo(mutableSetOf(), PhoneMediaTrash::isPhoneTrashId)
    fun appIds(ids: Set<String>) = ids - phoneIds(ids)
    fun restore(ids: Set<String>) {
        appIds(ids).takeIf { it.isNotEmpty() }?.let(onRestore)
        phoneIds(ids).takeIf { it.isNotEmpty() }?.let(onRestorePhone)
    }
    fun permanentDelete(ids: Set<String>) {
        appIds(ids).takeIf { it.isNotEmpty() }?.let(onDelete)
        phoneIds(ids).takeIf { it.isNotEmpty() }?.let(onDeletePhone)
    }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(phoneTab) { selected = emptySet() }
    var expanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteAll by remember { mutableStateOf(false) }

    LaunchedEffect(allItems.map { it.id }) {
        selected = selected.intersect(allItems.mapTo(mutableSetOf()) { it.id })
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(if (deleteAll) "Очистить корзину?" else "Удалить выбранные навсегда?") },
        text = {
            Text("Файлы будут удалены без возможности восстановления. Обычная история загрузок не изменится.")
        },
        confirmButton = {
            TextButton(onClick = {
                val ids = if (deleteAll) allItems.mapTo(mutableSetOf()) { it.id } else selected
                confirmDelete = false
                deleteAll = false
                if (ids.isNotEmpty()) permanentDelete(ids)
                selected = emptySet()
            }) { Text("Удалить навсегда", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = { confirmDelete = false; deleteAll = false }) { Text("Отмена") }
        },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column {
                    Text("Корзина")
                    Text(com.domenota.medialoader.ui.model.russianFileCount(items.size + phoneItems.size), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад") }
                },
                actions = {
                    IconButton(onClick = { grid = !grid }) {
                        Icon(if (grid) Icons.Rounded.ViewList else Icons.Rounded.GridView,
                            if (grid) "Показать списком" else "Показать сеткой")
                    }
                    Box {
                        IconButton(onClick = { expanded = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "Действия с корзиной")
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Очистить корзину") },
                                enabled = allItems.isNotEmpty(),
                                onClick = {
                                    expanded = false
                                    deleteAll = true
                                    confirmDelete = true
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (allItems.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            selected = if (selected.size == allItems.size) emptySet()
                            else allItems.mapTo(mutableSetOf()) { it.id }
                        }) { Text(if (selected.size == allItems.size) "Снять выделение" else "Выбрать всё") }
                        Spacer(Modifier.weight(1f))
                        Text("Выбрано: ${selected.size}", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { val ids = selected; selected = emptySet(); restore(ids) },
                            modifier = Modifier.weight(1f),
                            enabled = selected.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF087E67),
                                contentColor = Color.White,
                            ),
                        ) { Text("Восстановить") }
                        OutlinedButton(
                            onClick = { deleteAll = false; confirmDelete = true },
                            modifier = Modifier.weight(1f),
                            enabled = selected.isNotEmpty(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("Удалить") }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !phoneTab, onClick = { phoneTab = false },
                    label = { Text("Загрузчик · ${items.size}") })
                FilterChip(selected = phoneTab, onClick = { phoneTab = true },
                    label = { Text("Телефон · ${phoneItems.size}") })
            }
        if (allItems.isEmpty()) {
            Box(Modifier.fillMaxWidth().heightIn(min = 260.dp),
                contentAlignment = Alignment.Center) { Text("Корзина пуста") }
        } else if (grid) {
            LazyVerticalGrid(columns = GridCells.Adaptive(110.dp), modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(allItems, key = { it.id }) { item ->
                    Column {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clickable { onOpen(item.id) }) {
                            DownloadThumbnail(item.savedUri, null, item.kind, Modifier.fillMaxSize())
                            Box(Modifier.align(Alignment.TopStart).size(44.dp)
                                .toggleable(item.id in selected, role = Role.Checkbox, onValueChange = { value ->
                                    selected = if (value) selected + item.id else selected - item.id
                                }), contentAlignment = Alignment.Center) {
                                val checked = item.id in selected
                                Box(Modifier.size(24.dp).background(if (checked) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = .35f), CircleShape)
                                    .border(2.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                                    if (checked) Icon(Icons.Rounded.Check, null, Modifier.size(17.dp), tint = Color.White)
                                }
                            }
                        }
                        Text(item.title, maxLines = 2, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(allItems, key = { it.id }) { item ->
                    val checked = item.id in selected
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
                            Box(Modifier.size(42.dp)
                                .toggleable(value = checked, role = Role.Checkbox, onValueChange = { value ->
                                    selected = if (value) selected + item.id else selected - item.id
                                }), contentAlignment = Alignment.Center) {
                                val color = if (checked) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                Box(Modifier.size(23.dp).border(BorderStroke(2.dp, color), CircleShape)
                                    .background(if (checked) color else Color.Transparent, CircleShape),
                                    contentAlignment = Alignment.Center) {
                                    if (checked) Icon(Icons.Rounded.Check, null, Modifier.size(15.dp),
                                        tint = MaterialTheme.colorScheme.onPrimary)
                                }
                            }
                            Row(Modifier.weight(1f).clickable { onOpen(item.id) },
                                verticalAlignment = Alignment.CenterVertically) {
                                DownloadThumbnail(item.savedUri, null, item.kind, Modifier.size(56.dp))
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(item.title, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                                    Text(item.subtitle, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

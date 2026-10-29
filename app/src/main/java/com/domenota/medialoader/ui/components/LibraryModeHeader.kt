package com.domenota.medialoader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.domenota.medialoader.data.library.*

internal val AppMode.label: String get() = when (this) {
    AppMode.DOWNLOADER -> "Загрузчик"
    AppMode.GALLERY -> "Галерея"
    AppMode.MUSIC -> "Музыка"
}

@Composable
internal fun AppModeTitle(mode: AppMode, onChange: (AppMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.semantics { contentDescription = "Сменить режим" }
            .clickable { expanded = true }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(mode.label, fontSize = if (mode == AppMode.DOWNLOADER) 32.sp else 24.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Rounded.ExpandMore, null, Modifier.padding(start = 6.dp))
        }
        DropdownMenu(expanded, { expanded = false }) {
            AppMode.entries.forEach { item -> DropdownMenuItem(
                text = { Text(item.label) }, onClick = { expanded = false; onChange(item) },
                leadingIcon = { Icon(when (item) {
                    AppMode.DOWNLOADER -> Icons.Rounded.Download
                    AppMode.GALLERY -> Icons.Rounded.PhotoLibrary
                    AppMode.MUSIC -> Icons.Rounded.MusicNote
                }, null) },
                trailingIcon = { if (mode == item) Icon(Icons.Rounded.Check, null) }) }
        }
    }
}

@Composable
internal fun LibraryModeHeader(mode: AppMode, source: LibrarySource, onMode: (AppMode) -> Unit,
    onSource: (LibrarySource) -> Unit, onAccess: () -> Unit, onSettings: () -> Unit,
    onSearch: () -> Unit = {}, onPrivate: () -> Unit = {}) {
    var actionsMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 17.dp, end = 17.dp, top = 9.dp, bottom = 15.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { AppModeTitle(mode, onMode) }
            IconButton(onSearch) { Icon(Icons.Rounded.Search, "Поиск", Modifier.size(23.dp)) }
            IconButton(onPrivate) { Icon(Icons.Rounded.Lock, "Личное", Modifier.size(23.dp)) }
            Box {
                IconButton({ actionsMenu = true }) { Icon(Icons.Rounded.MoreVert, "Дополнительно", Modifier.size(23.dp)) }
                DropdownMenu(actionsMenu, { actionsMenu = false }) {
                    DropdownMenuItem({ Text("Настройки") }, { actionsMenu = false; onSettings() },
                        leadingIcon = { Icon(Icons.Rounded.Settings, null) })
                    if (source == LibrarySource.PHONE) DropdownMenuItem({ Text("Доступ к файлам") },
                        { actionsMenu = false; onAccess() }, leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) })
                }
            }
        }
        LibrarySourceSelector(source, onSource)
    }
}

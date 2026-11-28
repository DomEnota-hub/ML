package com.domenota.medialoader.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** Mode changes are owned exclusively by the navigation drawer. */
@Composable
internal fun AppModeTitle(mode: AppMode) {
    Text(mode.label, modifier = Modifier.padding(vertical = 6.dp),
        fontSize = if (mode == AppMode.DOWNLOADER) 32.sp else 24.sp,
        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun LibraryModeHeader(mode: AppMode, source: LibrarySource,
    onSource: (LibrarySource) -> Unit, onTrash: () -> Unit, onSettings: () -> Unit,
    onSearch: () -> Unit = {}, onPrivate: () -> Unit = {}, onVault: () -> Unit = {}, onHiddenTracks: (() -> Unit)? = null, onCalendar: (() -> Unit)? = null,
    unifiedDrawerRoutes:Boolean=false) {
    Column(Modifier.fillMaxWidth().padding(start = 17.dp, end = 17.dp, top = 9.dp, bottom = 15.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { AppModeTitle(mode) }
            if (mode == AppMode.MUSIC && onHiddenTracks != null) {
                IconButton(onHiddenTracks) {
                    Icon(Icons.Rounded.VisibilityOff, "Скрытые треки", Modifier.size(23.dp),
                        tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            onCalendar?.let { action -> IconButton(action) {
                Icon(Icons.Rounded.CalendarMonth, "Перейти к дате", Modifier.size(23.dp), tint = MaterialTheme.colorScheme.onSurface)
            } }
            IconButton(onSearch) { Icon(Icons.Rounded.Search, "Поиск", Modifier.size(23.dp)) }
            if(!unifiedDrawerRoutes){
                IconButton(onPrivate) { Icon(Icons.Rounded.Lock, "Личное", Modifier.size(23.dp)) }
                IconButton(onVault) { ArchiveActionIcon() }
            }
            if (mode == AppMode.GALLERY) {
                IconButton(onTrash) {
                    Icon(Icons.Rounded.DeleteOutline, "Корзина", Modifier.size(23.dp))
                }
            } else if(!unifiedDrawerRoutes) {
                IconButton(onSettings) {
                    Icon(Icons.Rounded.Settings, "Настройки", Modifier.size(23.dp))
                }
            }
        }
        LibrarySourceSelector(source, onSource)
    }
}

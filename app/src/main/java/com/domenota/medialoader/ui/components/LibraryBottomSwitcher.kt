package com.domenota.medialoader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.data.library.AppMode

@Composable
internal fun LibraryBottomSwitcher(mode: AppMode, selected: Int, onSelect: (Int) -> Unit) {
    val labels = if (mode == AppMode.GALLERY) listOf("Медиа", "Альбомы") else listOf("Треки", "Плейлисты")
    Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(40.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                labels.forEachIndexed { index, label ->
                    val active = selected == index
                    Row(Modifier.clip(RoundedCornerShape(30.dp))
                        .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else androidx.compose.ui.graphics.Color.Transparent)
                        .semantics { this.selected = active }.clickable(role = Role.Tab) { onSelect(index) }
                        .heightIn(min = 48.dp).padding(horizontal = 19.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        Icon(if (index == 1) { if (mode == AppMode.MUSIC) Icons.Rounded.QueueMusic else Icons.Rounded.PhotoLibrary }
                            else if (mode == AppMode.GALLERY) Icons.Rounded.Image else Icons.Rounded.MusicNote,
                            null, Modifier.size(20.dp), tint)
                        Text(label, color = tint, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.data.library.AppMode

@Composable
internal fun LibraryBottomSwitcher(mode: AppMode, selected: Int, onSelect: (Int) -> Unit) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < .5f
    val labels = if (mode == AppMode.GALLERY) listOf("Медиа", "Альбомы") else listOf("Треки", "Плейлисты")
    val switchBackground = if (isDark) Color(0xFF2A2A2E) else Color(0xFFE1E1E4)
    val switchSelection = if (isDark) Color(0xFF49494E) else Color(0xFFBFC0C4)
    val activeIcon = if (isDark) Color(0xFFF0F0F1) else Color(0xFF222226)
    val inactiveIcon = if (isDark) Color(0xFF99999F) else Color(0xFF6B6B73)

    Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp),
        contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(40.dp),
            color = switchBackground.copy(alpha = .9f)) {
            Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                labels.forEachIndexed { index, label ->
                    val active = selected == index
                    val tint = if (active) activeIcon else inactiveIcon
                    Row(
                        Modifier.clip(RoundedCornerShape(30.dp))
                            .background(if (active) switchSelection else Color.Transparent)
                            .semantics {
                                this.selected = active
                                contentDescription = label
                            }
                            .clickable(role = Role.Tab) { onSelect(index) }
                            .heightIn(min = 48.dp).width(76.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            if (index == 1) {
                                if (mode == AppMode.MUSIC) Icons.Rounded.QueueMusic
                                else Icons.Rounded.PhotoLibrary
                            } else if (mode == AppMode.GALLERY) Icons.Rounded.Image
                            else Icons.Rounded.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(23.dp),
                            tint = tint,
                        )
                    }
                }
            }
        }
    }
}

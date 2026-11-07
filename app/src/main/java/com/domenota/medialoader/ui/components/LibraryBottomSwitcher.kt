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
    val gallery = mode == AppMode.GALLERY
    val isDark = MaterialTheme.colorScheme.background.luminance() < .5f
    val labels = if (gallery) listOf("Медиа", "Альбомы") else listOf("Треки", "Плейлисты")
    val galleryBackground = if (isDark) Color(0xFF2A2A2E) else Color(0xFFE1E1E4)
    val gallerySelection = if (isDark) Color(0xFF49494E) else Color(0xFFBFC0C4)
    val galleryActiveIcon = if (isDark) Color(0xFFF0F0F1) else Color(0xFF222226)
    val galleryInactiveIcon = if (isDark) Color(0xFF99999F) else Color(0xFF6B6B73)

    Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp),
        contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(40.dp),
            color = if (gallery) galleryBackground.copy(alpha = .9f)
                else MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                labels.forEachIndexed { index, label ->
                    val active = selected == index
                    val background = if (gallery) {
                        if (active) gallerySelection else Color.Transparent
                    } else if (active) MaterialTheme.colorScheme.primary.copy(alpha = .12f)
                        else Color.Transparent
                    val tint = if (gallery) {
                        if (active) galleryActiveIcon else galleryInactiveIcon
                    } else if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    Row(
                        Modifier.clip(RoundedCornerShape(30.dp))
                            .background(background)
                            .semantics {
                                this.selected = active
                                contentDescription = label
                            }
                            .clickable(role = Role.Tab) { onSelect(index) }
                            .heightIn(min = 48.dp)
                            .then(if (gallery) Modifier.width(76.dp)
                                else Modifier.padding(horizontal = 19.dp)),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(if (index == 1) {
                            if (mode == AppMode.MUSIC) Icons.Rounded.QueueMusic else Icons.Rounded.PhotoLibrary
                        } else if (gallery) Icons.Rounded.Image else Icons.Rounded.MusicNote,
                            null, Modifier.size(if (gallery) 23.dp else 20.dp), tint)
                        if (!gallery) {
                            Spacer(Modifier.width(8.dp))
                            Text(label, color = tint, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

package com.domenota.medialoader.ui.screens.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.ui.components.DownloadThumbnail
import com.domenota.medialoader.ui.model.toUi

internal fun galleryQuickIcon(kind: GalleryQuickKind): ImageVector = when (kind) {
    GalleryQuickKind.ALL -> Icons.Rounded.PhotoLibrary
    GalleryQuickKind.CAMERA -> Icons.Rounded.PhotoCamera
    GalleryQuickKind.SCREENSHOTS -> Icons.Rounded.CropFree
    GalleryQuickKind.VIDEO -> Icons.Rounded.Videocam
    GalleryQuickKind.FAVORITES -> Icons.Rounded.Favorite
    GalleryQuickKind.ALBUM -> Icons.Rounded.Folder
}

/** Standard shortcuts and pinned albums share the same compact, neutral card geometry. */
@Composable
internal fun GalleryQuickAccessCell(
    shortcut: GalleryQuickItem,
    index: Int,
    dragged: Boolean = false,
    mediaExcluded: Boolean = false,
    onOpen: () -> Unit,
    onUnpin: (() -> Unit)? = null,
    onToggleMedia: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var menu by remember(shortcut.key) { mutableStateOf(false) }
    val isDark = MaterialTheme.colorScheme.background.luminance() < .5f
    val card = if (isDark) Color(0xFF242427) else Color(0xFFF0F0F2)
    val imageBackground = if (isDark) Color(0xFF37373B) else Color(0xFFDFDFE2)
    val primaryText = if (isDark) Color(0xFFEDEDEF) else Color(0xFF252527)
    val secondaryText = if (isDark) Color(0xFF99999F) else Color(0xFF707076)
    val marker = galleryQuickIcon(shortcut.kind)

    Surface(
        modifier = Modifier
            .padding(start = if (index % 2 == 0) 17.dp else 5.dp,
                end = if (index % 2 == 0) 5.dp else 17.dp,
                top = 4.dp, bottom = 4.dp)
            .fillMaxWidth().height(84.dp)
            .alpha(if (dragged) .35f else 1f),
        shape = RoundedCornerShape(16.dp),
        color = card,
        onClick = onOpen,
    ) {
        Row(Modifier.fillMaxSize().padding(9.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(60.dp).clip(RoundedCornerShape(11.dp))
                .background(imageBackground), contentAlignment = Alignment.Center) {
                Icon(marker, null, Modifier.size(27.dp), tint = secondaryText)
                shortcut.cover?.let { cover ->
                    DownloadThumbnail(
                        cover.savedUri,
                        cover.previewUrl,
                        cover.toUi().kind,
                        modifier = Modifier.fillMaxSize(),
                        progressive = true,
                    )
                }
                if (mediaExcluded) {
                    Box(Modifier.align(Alignment.TopStart).padding(3.dp)
                        .background(Color.Black.copy(alpha = .6f), RoundedCornerShape(5.dp))
                        .padding(2.dp)) {
                        Icon(Icons.Rounded.VisibilityOff, "Скрыто из «Медиа»",
                            Modifier.size(13.dp), tint = Color.White)
                    }
                }
                if (onUnpin != null) {
                    Box(Modifier.align(Alignment.TopEnd)) {
                        IconButton({ menu = true }, Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.MoreVert, "Управление альбомом ${shortcut.title}",
                                Modifier.size(19.dp), tint = Color.White)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Убрать из быстрого доступа") },
                                onClick = { menu = false; onUnpin() },
                                leadingIcon = { Icon(Icons.Rounded.VisibilityOff, null) },
                            )
                            if (onToggleMedia != null) {
                                DropdownMenuItem(
                                    text = { Text(if (mediaExcluded) "Показывать файлы в «Медиа»"
                                        else "Скрыть файлы из «Медиа»") },
                                    onClick = { menu = false; onToggleMedia() },
                                    leadingIcon = { Icon(Icons.Rounded.RemoveRedEye, null) },
                                )
                            }
                            if (onDelete != null) {
                                DropdownMenuItem(
                                    text = { Text("Удалить альбом", color = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; onDelete() },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.Delete, null,
                                            tint = MaterialTheme.colorScheme.error)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(shortcut.title, color = primaryText, fontSize = 12.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(marker, null, Modifier.size(14.dp), tint = secondaryText)
                    Text(shortcut.count.toString(), color = secondaryText, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Tool panels are navigational chrome, not separate destructive editing modes. */
internal enum class VideoWorkspaceTool(val label: String) {
    TRIM("Обрезка"),
    SPEED("Скорость"),
    CROP("Кадр"),
    ROTATE("Поворот"),
    COLOR("Цвет"),
    AUDIO("Звук"),
    REVERSE("Реверс"),
    TEXT("Текст"),
    GRAPHICS("Стикеры"),
    MORE("Ещё");
}

private fun VideoWorkspaceTool.icon(): ImageVector = when (this) {
    VideoWorkspaceTool.TRIM -> Icons.Rounded.ContentCut
    VideoWorkspaceTool.SPEED -> Icons.Rounded.Speed
    VideoWorkspaceTool.CROP -> Icons.Rounded.Crop
    VideoWorkspaceTool.ROTATE -> Icons.Rounded.RotateRight
    VideoWorkspaceTool.COLOR -> Icons.Rounded.Palette
    VideoWorkspaceTool.AUDIO -> Icons.Rounded.VolumeUp
    VideoWorkspaceTool.REVERSE -> Icons.Rounded.FastRewind
    VideoWorkspaceTool.TEXT -> Icons.Rounded.TextFields
    VideoWorkspaceTool.GRAPHICS -> Icons.Rounded.EmojiEmotions
    VideoWorkspaceTool.MORE -> Icons.Rounded.MoreHoriz
}

internal val videoToolYellow = Color(0xFFFFBE23)

@Composable
internal fun VideoWorkspaceToolbar(
    selected: VideoWorkspaceTool?,
    onSelect: (VideoWorkspaceTool?) -> Unit,
    enabled: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().height(82.dp).horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        VideoWorkspaceTool.entries.forEach { tool ->
            val active = tool == selected
            Column(
                modifier = Modifier.width(66.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Surface(
                    onClick = { onSelect(if (active) null else tool) },
                    enabled = enabled,
                    shape = RoundedCornerShape(15.dp),
                    color = if (active) videoToolYellow
                        else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(47.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            tool.icon(), contentDescription = tool.label,
                            modifier = Modifier.size(23.dp),
                            tint = if (active) Color.Black
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    tool.label, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp, maxLines = 1,
                )
            }
        }
    }
}

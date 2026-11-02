package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    TRANSITION("Переход"),
    EFFECTS("Эффекты"),
    AUDIO("Звук"),
    REVERSE("Реверс"),
    TEXT("Текст"),
    GRAPHICS("Стикеры"),
    EXPORT("Экспорт"),
    MORE("Ещё");
}

private fun VideoWorkspaceTool.icon(): ImageVector = when (this) {
    VideoWorkspaceTool.TRIM -> Icons.Rounded.ContentCut
    VideoWorkspaceTool.SPEED -> Icons.Rounded.Speed
    VideoWorkspaceTool.CROP -> Icons.Rounded.Crop
    VideoWorkspaceTool.ROTATE -> Icons.Rounded.RotateRight
    VideoWorkspaceTool.COLOR -> Icons.Rounded.Palette
    VideoWorkspaceTool.TRANSITION -> Icons.Rounded.AutoAwesomeMotion
    VideoWorkspaceTool.EFFECTS -> Icons.Rounded.AutoFixHigh
    VideoWorkspaceTool.AUDIO -> Icons.Rounded.VolumeUp
    VideoWorkspaceTool.REVERSE -> Icons.Rounded.FastRewind
    VideoWorkspaceTool.TEXT -> Icons.Rounded.TextFields
    VideoWorkspaceTool.GRAPHICS -> Icons.Rounded.EmojiEmotions
    VideoWorkspaceTool.EXPORT -> Icons.Rounded.HighQuality
    VideoWorkspaceTool.MORE -> Icons.Rounded.MoreHoriz
}

internal val videoToolYellow = Color(0xFFFFBE23)

private enum class VideoToolGroup(val title: String, val tools: List<VideoWorkspaceTool>) {
    MONTAGE("Монтаж", listOf(VideoWorkspaceTool.TRIM, VideoWorkspaceTool.SPEED,
        VideoWorkspaceTool.CROP, VideoWorkspaceTool.ROTATE, VideoWorkspaceTool.TRANSITION)),
    DESIGN("Оформление", listOf(VideoWorkspaceTool.TEXT, VideoWorkspaceTool.GRAPHICS,
        VideoWorkspaceTool.COLOR, VideoWorkspaceTool.EFFECTS)),
    AUDIO("Звук", listOf(VideoWorkspaceTool.AUDIO, VideoWorkspaceTool.REVERSE)),
    FINISH("Файл", listOf(VideoWorkspaceTool.EXPORT, VideoWorkspaceTool.MORE)),
}

private fun groupFor(tool: VideoWorkspaceTool?) =
    VideoToolGroup.entries.firstOrNull { tool in it.tools } ?: VideoToolGroup.MONTAGE

/**
 * Two-level tool dock mirrors the reference editor's tool hierarchy.
 * Sections remain visible and stable; the existing actions/panels are unchanged.
 */
@Composable
internal fun VideoWorkspaceToolbar(
    selected: VideoWorkspaceTool?,
    onSelect: (VideoWorkspaceTool?) -> Unit,
    enabled: Boolean,
) {
    var group by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(groupFor(selected))
    }
    androidx.compose.runtime.LaunchedEffect(selected) {
        if (selected != null) group = groupFor(selected)
    }
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            VideoToolGroup.entries.forEach { entry ->
                FilterChip(
                    selected = entry == group,
                    onClick = { group = entry },
                    label = { Text(entry.title) },
                    enabled = enabled,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = videoToolYellow,
                        selectedLabelColor = Color.Black,
                    ),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().height(76.dp).horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            group.tools.forEach { tool ->
                val active = tool == selected
                Column(
                    modifier = Modifier.width(66.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Surface(
                        onClick = { onSelect(if (active) null else tool) },
                        enabled = enabled,
                        shape = RoundedCornerShape(15.dp),
                        color = if (active) videoToolYellow
                            else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(45.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                tool.icon(), contentDescription = tool.label,
                                modifier = Modifier.size(22.dp),
                                tint = if (active) Color.Black
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        tool.label, color = if (active) videoToolYellow else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp, maxLines = 1,
                    )
                }
            }
        }
    }
}

package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Existing runtime actions remain stable while the shell is rebuilt to approved HTML v8. */
internal enum class VideoWorkspaceTool(val label: String) {
    TRIM("Обрезка"), SPEED("Скорость"), CROP("Кадр"), ROTATE("Поворот"),
    COLOR("Цвет"), TRANSITION("Переход"), EFFECTS("Эффекты"), AUDIO("Звук"),
    REVERSE("Реверс"), TEXT("Текст"), GRAPHICS("Стикеры"),
    EXPORT("Экспорт"), MORE("Ещё"),
    DELETE("Удалить"), SEPARATE_AUDIO("Разделить звук"),
    REPLACE("Заменить"), DUPLICATE("Копировать"),
    SPLIT("Разделить"), MUSIC_PICKER("Музыка"), ASPECT("Соотношение сторон"), COLOR_CHANNELS("Дополнительная коррекция")
}
internal val videoToolYellow = Color(0xFFFFC000)

internal enum class VideoReferenceSection { MAIN, EDITOR, MUSIC, TEXT }

private fun VideoWorkspaceTool.icon(): ImageVector = when (this) {
    VideoWorkspaceTool.TRIM -> VideoV8Icons.trim
    VideoWorkspaceTool.SPEED -> VideoV8Icons.speed
    VideoWorkspaceTool.CROP -> VideoV8Icons.crop
    VideoWorkspaceTool.ASPECT -> VideoV8Icons.aspect
    VideoWorkspaceTool.ROTATE -> Icons.Rounded.RotateRight
    VideoWorkspaceTool.COLOR, VideoWorkspaceTool.COLOR_CHANNELS -> Icons.Rounded.Tune
    VideoWorkspaceTool.TRANSITION -> Icons.Rounded.AutoAwesomeMotion
    VideoWorkspaceTool.EFFECTS -> VideoV8Icons.effects
    VideoWorkspaceTool.AUDIO -> VideoV8Icons.volume
    VideoWorkspaceTool.MUSIC_PICKER -> VideoV8Icons.music
    VideoWorkspaceTool.REVERSE -> VideoV8Icons.reverse
    VideoWorkspaceTool.TEXT -> VideoV8Icons.text
    VideoWorkspaceTool.GRAPHICS -> Icons.Rounded.EmojiEmotions
    VideoWorkspaceTool.EXPORT -> Icons.Rounded.HighQuality
    VideoWorkspaceTool.MORE -> VideoV8Icons.more
    VideoWorkspaceTool.DELETE -> VideoV8Icons.trash
    VideoWorkspaceTool.SEPARATE_AUDIO -> VideoV8Icons.separate
    VideoWorkspaceTool.REPLACE -> VideoV8Icons.replace
    VideoWorkspaceTool.DUPLICATE -> VideoV8Icons.copy
    VideoWorkspaceTool.SPLIT -> VideoV8Icons.editor
}

private data class ReferenceRootAction(
    val label: String,
    val icon: ImageVector,
    val action: VideoWorkspaceTool?,
    val section: VideoReferenceSection? = null,
    val colorManual: Boolean? = null,
)

private val rootActions = listOf(
    ReferenceRootAction("Редактор", VideoV8Icons.editor, null, VideoReferenceSection.EDITOR),
    ReferenceRootAction("Музыка", VideoV8Icons.music, VideoWorkspaceTool.MUSIC_PICKER, VideoReferenceSection.MUSIC),
    ReferenceRootAction("Надписи", VideoV8Icons.text, VideoWorkspaceTool.TEXT, VideoReferenceSection.TEXT),
    ReferenceRootAction("Соотношение сторон", VideoV8Icons.aspect, VideoWorkspaceTool.ASPECT),
    ReferenceRootAction("Откорректировать", VideoV8Icons.adjustment, VideoWorkspaceTool.COLOR, colorManual = true),
    ReferenceRootAction("Фильтры", VideoV8Icons.filters, VideoWorkspaceTool.COLOR, colorManual = false),
    ReferenceRootAction("Ещё", VideoV8Icons.more, VideoWorkspaceTool.MORE),
)

internal fun referenceRootLabels(): List<String> = rootActions.map { it.label }

private val editorActions = listOf(
    VideoWorkspaceTool.TRIM, VideoWorkspaceTool.SPLIT,
    VideoWorkspaceTool.SPEED, VideoWorkspaceTool.AUDIO,
    VideoWorkspaceTool.REVERSE, VideoWorkspaceTool.DELETE,
    VideoWorkspaceTool.SEPARATE_AUDIO, VideoWorkspaceTool.REPLACE,
    VideoWorkspaceTool.CROP, VideoWorkspaceTool.DUPLICATE,
    VideoWorkspaceTool.ROTATE, VideoWorkspaceTool.TRANSITION,
    VideoWorkspaceTool.EFFECTS,
)
private val musicActions = listOf(VideoWorkspaceTool.AUDIO)
private val textActions = listOf(VideoWorkspaceTool.TEXT, VideoWorkspaceTool.GRAPHICS)

/**
 * Native equivalent of HTML v8's ONE horizontally scrollable bottom dock.
 * The root never shows the old two-level group chips; child sections replace it
 * with a Back affordance and the corresponding tool row.
 * Golden reference: Google Drive /Загрузчик/Прототипы/MediaLoader_Видеоредактор_v8_две_строки.html
 */
@Composable
internal fun VideoWorkspaceToolbar(
    selected: VideoWorkspaceTool?,
    onSelect: (VideoWorkspaceTool?) -> Unit,
    enabled: Boolean,
    onColorManualMode: (Boolean) -> Unit = {},
    colorManualMode: Boolean = false,
    onDeleteClip: () -> Unit = {},
    onSeparateAudio: () -> Unit = {},
    onReplaceClip: () -> Unit = {},
    onDuplicateClip: () -> Unit = {},
    onSplitClip: () -> Unit = {},
    canSplitClip: Boolean = false,
    canDeleteClip: Boolean = false,
    canSeparateAudio: Boolean = false,
) {
    var section by remember { mutableStateOf(VideoReferenceSection.MAIN) }

    Row(
        Modifier.fillMaxWidth().height(89.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (section != VideoReferenceSection.MAIN) {
            IconButton(
                onClick = { section = VideoReferenceSection.MAIN; onSelect(null) },
                enabled = enabled,
                modifier = Modifier.width(43.dp).height(45.dp),
            ) { Icon(Icons.Rounded.ArrowBackIosNew, contentDescription = "К главному меню") }
        }
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState())
                .padding(start = if (section == VideoReferenceSection.MAIN) 4.dp else 0.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (section == VideoReferenceSection.MAIN) {
                rootActions.forEach { item ->
                    ReferenceDockItem(
                        label = item.label,
                        icon = item.icon,
                        active = item.action != null && item.action == selected &&
                            (item.colorManual == null || item.colorManual == colorManualMode),
                        enabled = enabled,
                        width = if (item.label == "Соотношение сторон") 72 else 60,
                        onClick = {
                            item.colorManual?.let(onColorManualMode)
                            section = item.section ?: VideoReferenceSection.MAIN
                            // Opening a section is navigation, not an edit reset.
                            if (item.action != null) onSelect(item.action)
                        },
                    )
                }
            } else {
                val actions = when (section) {
                    VideoReferenceSection.EDITOR -> editorActions
                    VideoReferenceSection.MUSIC -> musicActions
                    VideoReferenceSection.TEXT -> textActions
                    VideoReferenceSection.MAIN -> emptyList()
                }
                actions.forEach { tool ->
                    val itemLabel = if (tool == VideoWorkspaceTool.AUDIO)
                        "Громкость" else tool.label
                    ReferenceDockItem(
                        label = itemLabel,
                        icon = tool.icon(),
                        active = selected == tool,
                        enabled = enabled && when (tool) {
                            VideoWorkspaceTool.DELETE -> canDeleteClip
                            VideoWorkspaceTool.SPLIT -> canSplitClip
                            VideoWorkspaceTool.SEPARATE_AUDIO -> canSeparateAudio
                            else -> true
                        },
                        width = if (tool == VideoWorkspaceTool.SEPARATE_AUDIO) 84 else 72,
                        onClick = {
                            when (tool) {
                                VideoWorkspaceTool.DELETE -> onDeleteClip()
                                VideoWorkspaceTool.SPLIT -> onSplitClip()
                                VideoWorkspaceTool.SEPARATE_AUDIO -> onSeparateAudio()
                                VideoWorkspaceTool.REPLACE -> onReplaceClip()
                                VideoWorkspaceTool.DUPLICATE -> onDuplicateClip()
                                else -> onSelect(if (selected == tool) null else tool)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReferenceDockItem(
    label: String,
    icon: ImageVector,
    active: Boolean,
    enabled: Boolean,
    width: Int,
    onClick: () -> Unit,
) {
    androidx.compose.material3.TextButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.width(width.dp).height(83.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(
            Modifier.fillMaxSize().padding(top = 7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon, contentDescription = label,
                modifier = Modifier.size(22.dp),
                tint = if (active) videoToolYellow else Color(0xFFECECEC),
            )
            val lines = when (label) {
                "Соотношение сторон" -> "Соотношение\nсторон"
                "Откорректировать" -> "Откорректи\nровать"
                "Разделить звук" -> "Разделить\nзвук"
                else -> label
            }
            Text(
                lines, color = if (active) videoToolYellow else Color(0xFFECECEC),
                fontSize = 9.sp, lineHeight = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 2, softWrap = false,
            )
        }
    }
}

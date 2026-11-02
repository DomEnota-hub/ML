package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import com.domenota.medialoader.ui.components.AppSlider as Slider
import com.domenota.medialoader.ui.components.UnifiedTextTab
import com.domenota.medialoader.ui.components.UnifiedTextTabs
import com.domenota.medialoader.ui.components.UnifiedTextStyle
import com.domenota.medialoader.ui.components.EditorFontPicker
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.editor.*
import kotlin.math.roundToLong

/** One editing surface for timed text, vector stickers and user-supplied PNG/JPG images.
 * The parent video tool sheet owns vertical scrolling; nesting another verticalScroll
 * inside its unbounded measurement crashes Compose when Text/Graphics is opened.
 */
@Composable
internal fun VideoOverlayPanel(
    graphicsTab: Boolean,
    durationMs: Long,
    playheadMs: Long,
    texts: List<TextLayer>,
    graphics: List<VideoGraphicLayer>,
    selectedId: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
    onAddText: () -> Unit,
    onAddGraphic: (VideoGraphicKind) -> Unit,
    onAddImage: () -> Unit,
    onChangeText: (TextLayer) -> Unit,
    onChangeGraphic: (VideoGraphicLayer) -> Unit,
    onRemoveText: (String) -> Unit,
    onRemoveGraphic: (String) -> Unit,
    onSeek: (Long) -> Unit,
) {
    var selectedTextTab by remember { mutableStateOf(UnifiedTextTab.KEYBOARD) }
    val text = if (!graphicsTab) texts.firstOrNull { it.id == selectedId } else null
    val graphic = if (graphicsTab) graphics.firstOrNull { it.id == selectedId } else null
    val validDuration = durationMs.coerceAtLeast(1L)
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer,
        RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
        .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (graphicsTab) "Графика и стикеры" else "Надписи",
                style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { onSelect(null) }, enabled = enabled && selectedId != null) {
                Text("Снять выделение")
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!graphicsTab) {
                OutlinedButton(onClick = onAddText, enabled = enabled && texts.size < 8) {
                    Text("+ Текст")
                }
            } else {
                listOf(VideoGraphicKind.STAR, VideoGraphicKind.CIRCLE,
                    VideoGraphicKind.RECTANGLE, VideoGraphicKind.ARROW,
                    VideoGraphicKind.EMOJI).forEach { kind ->
                    OutlinedButton(onClick = { onAddGraphic(kind) },
                        enabled = enabled && graphics.size < 12) {
                        Text(kind.label)
                    }
                }
                OutlinedButton(onClick = onAddImage,
                    enabled = enabled && graphics.size < 12) { Text("+ Фото/PNG") }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            val entries = if (graphicsTab)
                graphics.mapIndexed { index, item -> item.id to "Графика ${index + 1}" }
            else texts.mapIndexed { index, item -> item.id to "Текст ${index + 1}" }
            entries.forEach { (id, label) ->
                FilterChip(selected = id == selectedId, enabled = enabled,
                    onClick = { onSelect(id) }, label = { Text(label) })
            }
        }
        if (text == null && graphic == null) {
            Text("Добавь слой или выбери существующий. Надпись можно перетащить прямо на кадре.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Положение, стиль и длительность",
                style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = {
                if (text != null) onRemoveText(text.id) else graphic?.let { onRemoveGraphic(it.id) }
            }, enabled = enabled) { Text("Удалить") }
        }
        if (text != null) {
            // Photo and video use the same language and palette for their text controls.
            UnifiedTextTabs(selected = selectedTextTab,
                onSelect = { selectedTextTab = it })
            when (selectedTextTab) {
                UnifiedTextTab.KEYBOARD -> OutlinedTextField(value = text.text,
                    onValueChange = { onChangeText(text.copy(text = it.take(500))) },
                    label = { Text("Надпись") }, modifier = Modifier.fillMaxWidth(),
                    maxLines = 3, enabled = enabled)
                UnifiedTextTab.STYLE -> UnifiedTextStyle(text, onChangeText, enabled)
                UnifiedTextTab.FONT -> {
                    Text("Библиотека шрифтов", style = MaterialTheme.typography.labelMedium)
                    EditorFontPicker(text.fontId) { onChangeText(text.copy(fontId = it)) }
                }
            }
        }
        if (graphic != null) {
            if (graphic.kind == VideoGraphicKind.EMOJI) {
                OutlinedTextField(value = graphic.emoji,
                    onValueChange = { onChangeGraphic(graphic.copy(emoji = it.take(16))) },
                    label = { Text("Символ или эмодзи") }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            if (graphic.kind == VideoGraphicKind.IMAGE) {
                Text("Изображение: ${graphic.sourceUri?.substringAfterLast('/') ?: ""}",
                    maxLines = 1, style = MaterialTheme.typography.labelSmall)
            } else {
                OverlayPalette(graphic.color, enabled) {
                    onChangeGraphic(graphic.copy(color = it))
                }
            }
            OverlaySlider("Размер", graphic.size, .03f.. .85f, enabled) {
                onChangeGraphic(graphic.copy(size = it))
            }
            OverlaySlider("Непрозрачность", graphic.opacity, 0f..1f, enabled) {
                onChangeGraphic(graphic.copy(opacity = it))
            }
        }
        val x = text?.centerX ?: graphic!!.centerX
        val y = text?.centerY ?: graphic!!.centerY
        val rotation = text?.rotation ?: graphic!!.rotation
        OverlaySlider("Горизонталь", x, 0f..1f, enabled) { v ->
            if (text != null) onChangeText(text.copy(centerX = v))
            else graphic?.let { onChangeGraphic(it.copy(centerX = v)) }
        }
        OverlaySlider("Вертикаль", y, 0f..1f, enabled) { v ->
            if (text != null) onChangeText(text.copy(centerY = v))
            else graphic?.let { onChangeGraphic(it.copy(centerY = v)) }
        }
        OverlaySlider("Поворот", rotation, -180f..180f, enabled) { v ->
            if (text != null) onChangeText(text.copy(rotation = v))
            else graphic?.let { onChangeGraphic(it.copy(rotation = v)) }
        }
        val from = text?.startMs ?: graphic!!.startMs
        val to = text?.endMs ?: graphic?.endMs ?: validDuration
        Text("Интервал на шкале: ${from / 1000f}–${to / 1000f} с",
            style = MaterialTheme.typography.labelMedium)
        OverlaySlider("Начало", from.toFloat(),
            0f..(validDuration - 1).toFloat().coerceAtLeast(1f), enabled) { v ->
            val start = v.roundToLong().coerceIn(0L, validDuration - 1L)
            if (text != null) onChangeText(text.copy(startMs = start,
                endMs = maxOf(start + 1L, to).coerceAtMost(validDuration)))
            else graphic?.let { onChangeGraphic(it.copy(startMs = start,
                endMs = maxOf(start + 1L, to).coerceAtMost(validDuration))) }
        }
        val minEnd = (from + 1L).coerceAtMost(validDuration)
        OverlaySlider("Конец", to.toFloat(),
            minEnd.toFloat()..validDuration.toFloat().coerceAtLeast(minEnd.toFloat()+1f),
            enabled) { v ->
            val end = v.roundToLong().coerceIn(minEnd, validDuration)
            if (text != null) onChangeText(text.copy(endMs = end))
            else graphic?.let { onChangeGraphic(it.copy(endMs = end)) }
        }
        TextButton(onClick = { onSeek(from.coerceIn(0L, validDuration - 1L)) },
            enabled = enabled) { Text("Перейти к появлению элемента") }
        Text("Элементы отображаются по времени всего проекта; их положение не зависит от " +
            "разрешения, скорости и ориентации отдельных клипов.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun OverlayPalette(current: Int, enabled: Boolean, onColor: (Int) -> Unit) {
    Text("Цвет", style = MaterialTheme.typography.labelSmall)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf(
            0xFFFFFFFF.toInt() to "Белый", 0xFF000000.toInt() to "Чёрный",
            0xFFFFC629.toInt() to "Жёлтый", 0xFFFF5555.toInt() to "Красный",
            0xFF49B2FF.toInt() to "Синий", 0xFF56DD8A.toInt() to "Зелёный",
            0xFFB78AFF.toInt() to "Фиолетовый",
        ).forEach { (argb, name) ->
            FilterChip(selected = current == argb, enabled = enabled,
                onClick = { onColor(argb) }, label = { Text(name) })
        }
    }
}

@Composable
private fun OverlaySlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    enabled: Boolean, onValueChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text("%.2f".format(java.util.Locale.US, value),
            style = MaterialTheme.typography.labelSmall)
    }
    Slider(value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onValueChange, valueRange = range, enabled = enabled)
}

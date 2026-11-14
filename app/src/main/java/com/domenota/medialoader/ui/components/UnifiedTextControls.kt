package com.domenota.medialoader.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.data.editor.TextLayerAlignment
import com.domenota.medialoader.data.editor.TextLayer

/** The same text component on the photo editor and video overlay editor. */
internal val textEditorAccent = editorSliderYellow

internal enum class UnifiedTextTab(val title: String) {
    KEYBOARD("Клавиатура"), STYLE("Стиль"), FONT("Шрифт")
}

@Composable
internal fun UnifiedTextTabs(
    selected: UnifiedTextTab,
    onSelect: (UnifiedTextTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        UnifiedTextTab.entries.forEach { tab ->
            TextButton(onClick = { onSelect(tab) }, modifier = Modifier.weight(1f)) {
                Text(tab.title, maxLines = 1,
                    color = if (tab == selected) Color.White else Color.LightGray,
                    style = if (tab == selected) MaterialTheme.typography.titleSmall
                        else MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun UnifiedTextStyle(
    layer: TextLayer,
    onChange: (TextLayer) -> Unit,
    enabled: Boolean = true,
    showSize: Boolean = true,
    onPickColor: (() -> Unit)? = null,
) {
    Text("Цвет текста", style = MaterialTheme.typography.labelLarge)
    val colors = listOf(
        0xFFFFFFFF.toInt(), 0xFF121212.toInt(), 0xFFAAAAAA.toInt(),
        0xFFFF4E63.toInt(), 0xFFFF7D54.toInt(), 0xFFFFD648.toInt(),
        0xFF66DD99.toInt(), 0xFF74C6FF.toInt()
    )
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        colors.forEach { color ->
            val active = layer.color == color
            Box(Modifier.size(40.dp)
                .border(if (active) 3.dp else 1.dp,
                    if (active) textEditorAccent else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f),
                    CircleShape)
                .padding(5.dp).background(Color(color), CircleShape)
                .clickable(enabled) { onChange(layer.copy(color = color)) })
        }
        if (onPickColor != null) OutlinedIconButton(onClick = onPickColor,
            enabled = enabled) {
            Icon(Icons.Rounded.Colorize, contentDescription = "Пипетка")
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextStyleToggle("Жирный", "B", layer.bold, enabled, Modifier.weight(1f)) {
            onChange(layer.copy(bold = !layer.bold))
        }
        TextStyleToggle("Контур", "A", layer.outline > 0f, enabled, Modifier.weight(1f)) {
            onChange(layer.copy(outline = if (layer.outline > 0f) 0f else .06f))
        }
        TextStyleToggle("Тень", "A", layer.shadow, enabled, Modifier.weight(1f)) {
            onChange(layer.copy(shadow = !layer.shadow))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(TextLayerAlignment.LEFT to "Слева", TextLayerAlignment.CENTER to "По центру",
            TextLayerAlignment.RIGHT to "Справа").forEach { (alignment, title) ->
            TextStyleToggle(title, when (alignment) {
                TextLayerAlignment.LEFT -> "≡"; TextLayerAlignment.CENTER -> "≡"; TextLayerAlignment.RIGHT -> "≡"
            }, layer.alignment == alignment, enabled, Modifier.weight(1f)) {
                onChange(layer.copy(alignment = alignment))
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Прозрачность", style = MaterialTheme.typography.labelMedium)
        CompactTextSlider(
            value = layer.opacity, onValueChange = { onChange(layer.copy(opacity = it)) },
            valueRange = 0f..1f, enabled = enabled,
            modifier = Modifier.weight(1f))
        Text("${(layer.opacity * 100f).toInt()} %",
            style = MaterialTheme.typography.labelSmall)
    }
    if (showSize) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Размер", style = MaterialTheme.typography.labelMedium)
        CompactTextSlider(value = layer.size.coerceIn(.02f, .25f),
            onValueChange = { onChange(layer.copy(size = it)) },
            valueRange = .02f.. .25f, enabled = enabled,
            modifier = Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Обводка", style = MaterialTheme.typography.labelMedium)
        CompactTextSlider(value = layer.outline.coerceIn(0f, .15f),
            onValueChange = { onChange(layer.copy(outline = it)) },
            valueRange = 0f.. .15f, enabled = enabled,
            modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = layer.outlineColor == android.graphics.Color.BLACK,
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = textEditorAccent,
                selectedLabelColor = Color.Black),
            onClick = { onChange(layer.copy(outlineColor = android.graphics.Color.BLACK)) },
            enabled = enabled, label = { Text("Тёмная обводка") })
        FilterChip(selected = layer.outlineColor == android.graphics.Color.WHITE,
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = textEditorAccent,
                selectedLabelColor = Color.Black),
            onClick = { onChange(layer.copy(outlineColor = android.graphics.Color.WHITE)) },
            enabled = enabled, label = { Text("Светлая") })
    }
}

@Composable
private fun TextStyleToggle(title: String, symbol: String, selected: Boolean, enabled: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.height(66.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = if (selected) textEditorAccent else Color(0xFF2C2C2C),
        contentColor = if (selected) Color.Black else Color.White) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            val ink = LocalContentColor.current
            Canvas(Modifier.size(24.dp)) {
                if (title in listOf("Слева", "По центру", "Справа")) {
                    listOf(.85f, .58f, .85f, .48f).forEachIndexed { i, fraction ->
                        val length = size.width * fraction
                        val x = when (title) { "Слева" -> 0f; "Справа" -> size.width - length; else -> (size.width - length) / 2 }
                        drawLine(ink, Offset(x, size.height * (.2f + i * .2f)),
                            Offset(x + length, size.height * (.2f + i * .2f)),
                            strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
                    }
                } else {
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = ink.toArgb(); textSize = size.height * .87f
                        textAlign = android.graphics.Paint.Align.CENTER
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        if (title == "Контур") { style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.dp.toPx() }
                    }
                    if (title == "Тень") {
                        paint.alpha = 80
                        drawContext.canvas.nativeCanvas.drawText(symbol, size.width / 2 + 2.dp.toPx(), size.height * .83f, paint)
                        paint.alpha = 255
                    }
                    drawContext.canvas.nativeCanvas.drawText(symbol, size.width / 2, size.height * .77f, paint)
                }
            }
            Text(title, fontSize = 10.sp)
        }
    }
}

/** Shared accessible round slider for the compact text controls. */
@Composable
internal fun CompactTextSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    EditorSlider(value=value,onValueChange=onValueChange,valueRange=valueRange,
        enabled=enabled,modifier=modifier)
}

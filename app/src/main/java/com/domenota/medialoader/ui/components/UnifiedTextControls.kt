package com.domenota.medialoader.ui.components

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
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.editor.TextLayer

/** The same text component on the photo editor and video overlay editor. */
internal val textEditorAccent = Color(0xFFFFBD19)

internal enum class UnifiedTextTab(val title: String) {
    KEYBOARD("Клавиатура"), STYLE("Стиль"), FONT("Шрифт")
}

@Composable
internal fun UnifiedTextTabs(
    selected: UnifiedTextTab,
    onSelect: (UnifiedTextTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = textEditorAccent
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        UnifiedTextTab.entries.forEach { tab ->
            TextButton(onClick = { onSelect(tab) }, modifier = Modifier.weight(1f)) {
                Text(tab.title, maxLines = 1,
                    color = if (tab == selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Размер", style = MaterialTheme.typography.labelMedium)
        Slider(value = layer.size.coerceIn(.02f, .25f),
            onValueChange = { onChange(layer.copy(size = it)) },
            valueRange = .02f.. .25f, enabled = enabled,
            modifier = Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Обводка", style = MaterialTheme.typography.labelMedium)
        Slider(value = layer.outline.coerceIn(0f, .15f),
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

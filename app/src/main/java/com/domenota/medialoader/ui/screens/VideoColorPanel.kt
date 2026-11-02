package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import com.domenota.medialoader.ui.components.AppSlider as Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.editor.VideoColorAdjustments
import com.domenota.medialoader.data.editor.VideoColorGrade
import com.domenota.medialoader.data.editor.VideoFilterGroup
import com.domenota.medialoader.data.editor.VideoFilterPreset
import java.util.Locale

/** Only the active clip is edited; filters and manual color changes compose non-destructively. */
@Composable
internal fun VideoColorPanel(
    color: VideoColorGrade,
    enabled: Boolean,
    onColorChange: (VideoColorGrade) -> Unit,
    manualByDefault: Boolean = false,
) {
    var showManual by remember { mutableStateOf(manualByDefault) }
    LaunchedEffect(manualByDefault) { showManual = manualByDefault }
    var group by remember { mutableStateOf(VideoFilterGroup.BASIC) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Цвет выбранного клипа", style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = { onColorChange(VideoColorGrade()) },
            enabled = enabled && !color.isNeutral) { Text("Сбросить") }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = !showManual, onClick = { showManual = false },
            label = { Text("Фильтры") })
        FilterChip(selected = showManual, onClick = { showManual = true },
            label = { Text("Коррекция") })
    }
    if (!showManual) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VideoFilterGroup.entries.forEach { tab ->
                FilterChip(selected = group == tab, enabled = enabled,
                    onClick = { group = tab }, label = { Text(tab.label) })
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (group != VideoFilterGroup.BASIC) {
                FilterChip(selected = color.preset == VideoFilterPreset.ORIGINAL,
                    enabled = enabled, onClick = {
                        onColorChange(color.copy(preset = VideoFilterPreset.ORIGINAL))
                    }, label = { Text("Оригинал") })
            }
            VideoFilterPreset.entries.filter { it.group == group }.forEach { preset ->
                FilterChip(selected = color.preset == preset, enabled = enabled,
                    onClick = { onColorChange(color.copy(preset = preset)) },
                    label = { Text(preset.label) })
            }
        }
        val percent = (color.intensity * 100).toInt()
        Text("Сила фильтра: $percent %", style = MaterialTheme.typography.bodySmall)
        Slider(value = color.intensity, onValueChange = {
            onColorChange(color.copy(intensity = it))
        }, enabled = enabled && color.preset != VideoFilterPreset.ORIGINAL,
            valueRange = 0f..1f)
        Text("Ручные настройки сохраняются при смене фильтра.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        val manual = color.manual
        ColorAdjustmentSlider("Яркость", manual.brightness, -.5f.. .5f, enabled) {
            onColorChange(color.copy(manual = manual.copy(brightness = it)))
        }
        ColorAdjustmentSlider("Контраст", manual.contrast, -.8f.. .8f, enabled) {
            onColorChange(color.copy(manual = manual.copy(contrast = it)))
        }
        ColorAdjustmentSlider("Насыщенность", manual.saturation, -100f..100f, enabled) {
            onColorChange(color.copy(manual = manual.copy(saturation = it)))
        }
        ColorAdjustmentSlider("Тон", manual.hue, -180f..180f, enabled) {
            onColorChange(color.copy(manual = manual.copy(hue = it)))
        }
        ColorAdjustmentSlider("Светлота", manual.lightness, -75f..75f, enabled) {
            onColorChange(color.copy(manual = manual.copy(lightness = it)))
        }
        Text("Цветовые каналы RGB", style = MaterialTheme.typography.titleSmall)
        ColorAdjustmentSlider("Красный", manual.red, 0f..2f, enabled) {
            onColorChange(color.copy(manual = manual.copy(red = it)))
        }
        ColorAdjustmentSlider("Зелёный", manual.green, 0f..2f, enabled) {
            onColorChange(color.copy(manual = manual.copy(green = it)))
        }
        ColorAdjustmentSlider("Синий", manual.blue, 0f..2f, enabled) {
            onColorChange(color.copy(manual = manual.copy(blue = it)))
        }
        TextButton(onClick = {
            onColorChange(color.copy(manual = VideoColorAdjustments()))
        }, enabled = enabled && !manual.isNeutral) { Text("Сбросить ручную коррекцию") }
    }
}

@Composable
private fun ColorAdjustmentSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text("%.2f".format(Locale.US, value), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onChange, enabled = enabled, valueRange = range)
}

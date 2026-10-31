package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.editor.*
import kotlin.math.roundToLong

/** Visible cut and clip effects, all controls act on a single selected clip. */
@Composable
internal fun VideoStage7EffectsPanel(
    project: VideoProjectDraft?,
    selectedClipId: String,
    transitionTool: Boolean,
    enabled: Boolean,
    onTransition: (VideoTransitionDraft) -> Unit,
    onSpecialFx: (VideoSpecialFxDraft) -> Unit,
) {
    if (project == null) {
        Text("Проект пока недоступен", style = MaterialTheme.typography.bodySmall)
        return
    }
    val index = project.indexOf(selectedClipId)
    val clip = project.clips.getOrNull(index)
    if (clip == null) {
        Text("Выбери клип для настройки", style = MaterialTheme.typography.bodySmall)
        return
    }
    if (transitionTool) {
        Text("Переход после клипа ${index + 1}",
            style = MaterialTheme.typography.titleSmall)
        if (index == project.clips.lastIndex) {
            Text("Это последний клип. Добавь следующий, чтобы настроить переход.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
            return
        }
        val selected = clip.transitionOut
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            VideoTransitionType.entries.forEach { type ->
                FilterChip(selected = selected.type == type, enabled = enabled,
                    onClick = { onTransition(selected.copy(type = type)) },
                    label = { Text(type.label) })
            }
        }
        if (selected.enabled) {
            Text("Длительность: ${"%.1f".format(java.util.Locale.US, selected.durationMs / 1000f)} с",
                style = MaterialTheme.typography.bodySmall)
            val next = project.clips[index + 1]
            val maximum = minOf(3000L, clip.durationMs, next.durationMs)
                .coerceAtLeast(100L)
            Slider(
                value = selected.durationMs.coerceIn(100L, maximum).toFloat(),
                valueRange = 100f..maximum.coerceAtLeast(101L).toFloat(),
                enabled = enabled && maximum > 100L,
                onValueChange = { value ->
                    onTransition(selected.copy(
                        durationMs = value.roundToLong().coerceIn(100L, maximum)))
                },
            )
        }
        Text("Эффект захватывает конец текущего и начало следующего клипа. " +
            "Время проекта и синхронизация музыки сохраняются.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Затемнение, вспышка и шторка — без перекрытия видеокадров. " +
            "Это не crossfade двух потоков.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text("Видеоэффект клипа ${index + 1}",
            style = MaterialTheme.typography.titleSmall)
        val selected = clip.specialFx
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            VideoSpecialFxType.entries.forEach { type ->
                FilterChip(selected = selected.type == type, enabled = enabled,
                    onClick = { onSpecialFx(selected.copy(type = type)) },
                    label = { Text(type.label) })
            }
        }
        if (selected.type != VideoSpecialFxType.NONE) {
            Text("Сила эффекта: ${(selected.intensity * 100).roundToLong()} %",
                style = MaterialTheme.typography.bodySmall)
            Slider(value = selected.intensity, valueRange = 0f..1f,
                enabled = enabled, onValueChange = { value ->
                    onSpecialFx(selected.copy(intensity = value))
                })
        }
        Text("Эффект действует только на выбранный клип. Цветовые настройки, " +
            "текст, звук и скорость остаются самостоятельными.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

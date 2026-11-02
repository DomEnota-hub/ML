package com.domenota.medialoader.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import com.domenota.medialoader.ui.components.AppSlider as Slider
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
    val context = LocalContext.current
    var previews by remember(clip.sourceUri, clip.inMs) {
        mutableStateOf<Map<VideoSpecialFxType, Bitmap>>(emptyMap())
    }
    LaunchedEffect(clip.sourceUri, clip.inMs, transitionTool) {
        if (!transitionTool) {
            previews = withContext(Dispatchers.IO) {
                VideoSpecialFxType.entries.mapNotNull { type ->
                    VideoThumbnailMemoryCache.special(context, clip, type)?.let { type to it }
                }.toMap()
            }
        }
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
                VideoEffectChoiceCard(type.label, selected.type == type, enabled,
                    transition = true, onClick = { onTransition(selected.copy(type = type)) })
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
                VideoEffectChoiceCard(type.label, selected.type == type, enabled,
                    transition = false, preview = previews[type],
                    onClick = { onSpecialFx(selected.copy(type = type)) })
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

/** Compact selectable effect cards; selection updates the existing Media3 preview pipeline. */
@Composable
private fun VideoEffectChoiceCard(
    title: String, selected: Boolean, enabled: Boolean,
    transition: Boolean, preview: Bitmap? = null, onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.width(108.dp).height(68.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) videoToolYellow else
            MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp,
            videoToolYellow) else null,
    ) {
        if (preview != null) {
            Box(Modifier.fillMaxSize()) {
                Image(preview.asImageBitmap(), contentDescription = title,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = .68f))
                    .padding(horizontal = 5.dp, vertical = 5.dp)) {
                    Text(title, maxLines = 1,
                        style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Icon(if (transition) Icons.Rounded.SwapHoriz else Icons.Rounded.AutoAwesome,
                    contentDescription = null, modifier = Modifier.size(21.dp),
                    tint = if (selected) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(3.dp))
                Text(title, maxLines = 1,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) Color.Black else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.editor.*

@Composable
internal fun VideoExportPanel(
    settings: VideoExportSettings,
    sourceWidth: Int?,
    sourceHeight: Int?,
    cropWidth: Float,
    cropHeight: Float,
    rotationDegrees: Int,
    estimatedFrameRate: Int,
    enabled: Boolean,
    onSettingsChange: (VideoExportSettings) -> Unit,
) {
    Text("Параметры экспорта MP4", style = MaterialTheme.typography.titleMedium)
    Text("Разрешение", style = MaterialTheme.typography.labelMedium)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        VideoExportResolution.entries.forEach { item ->
            FilterChip(selected = settings.resolution == item, enabled = enabled,
                onClick = { onSettingsChange(settings.copy(resolution = item)) },
                label = { Text(item.label) })
        }
    }
    Text("Частота кадров (максимум)", style = MaterialTheme.typography.labelMedium)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        VideoExportFrameRate.entries.forEach { item ->
            FilterChip(selected = settings.frameRate == item, enabled = enabled,
                onClick = { onSettingsChange(settings.copy(frameRate = item)) },
                label = { Text(item.label) })
        }
    }
    Text("Качество видео", style = MaterialTheme.typography.labelMedium)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        VideoExportQuality.entries.forEach { item ->
            FilterChip(selected = settings.quality == item, enabled = enabled,
                onClick = { onSettingsChange(settings.copy(quality = item)) },
                label = { Text(item.label) })
        }
    }
    if (sourceWidth != null && sourceHeight != null &&
        sourceWidth > 0 && sourceHeight > 0) {
        val output = settings.dimensions(sourceWidth, sourceHeight,
            cropWidth, cropHeight, rotationDegrees)
        val megabits = settings.targetBitrate(output, estimatedFrameRate) / 1_000_000f
        Text("Запрос: ${output.width} × ${output.height} · H.264 / MP4 · " +
            "%.1f".format(java.util.Locale.US, megabits) + " Мбит/с",
            style = MaterialTheme.typography.bodyMedium)
    } else {
        Text("Определяем размер первого клипа…",
            style = MaterialTheme.typography.labelSmall)
    }
    Text("Исходники с разной ориентацией помещаются в общий кадр " +
        "без растягивания. Запрошенные FPS, битрейт и разрешение могут " +
        "быть скорректированы кодеком телефона. Низкий FPS не повышается " +
        "искусственно; фото преобразуются в статичные видеокадры.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

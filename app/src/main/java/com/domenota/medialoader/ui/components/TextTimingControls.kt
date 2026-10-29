package com.domenota.medialoader.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.editor.TextLayer
import com.domenota.medialoader.data.editor.TextTiming

@Composable
internal fun TextTimingControls(layer: TextLayer, position: Long, duration: Long, canPlay: Boolean,
    onChange: (TextLayer) -> Unit, onSeek: (Long) -> Unit, onPlayInterval: () -> Unit) {
    val end = layer.endMs ?: duration
    Text("Отметь начало и конец по кадру или услышанной фразе.", style = MaterialTheme.typography.bodySmall)
    Text(if (layer.visibleAt(position)) "Сейчас надпись видна" else "Сейчас надпись вне выбранного интервала",
        style = MaterialTheme.typography.bodySmall)
    Text("Появление · ${timingTime(layer.startMs)}", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onChange(TextTiming.startAt(layer, position, duration)) },
            enabled = position < end) { Text("Начало здесь") }
        OutlinedButton(onClick = { onSeek(layer.startMs) }) { Text("К началу") }
    }
    AppSlider(layer.startMs.toFloat(), { value ->
        val next = TextTiming.startAt(layer, value.toLong(), duration)
        onChange(next); onSeek(next.startMs)
    }, valueRange = 0f..(end - 1).toFloat())
    Text("Исчезновение · ${timingTime(end)}", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onChange(TextTiming.endAt(layer, position, duration)) },
            enabled = position > layer.startMs) { Text("Конец здесь") }
        OutlinedButton(onClick = { onSeek(end) }) { Text("К концу") }
    }
    AppSlider(end.toFloat(), { value ->
        val next = TextTiming.endAt(layer, value.toLong(), duration)
        onChange(next); onSeek(next.endMs!!)
    }, valueRange = (layer.startMs + 1).toFloat()..duration.toFloat())
    if (canPlay) OutlinedButton(onClick = onPlayInterval, modifier = Modifier.fillMaxWidth()) {
        Text("Просмотреть выбранный интервал со звуком")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onSeek(position - 100) }) { Text("−0,1 с") }
        OutlinedButton(onClick = { onSeek(position + 100) }) { Text("+0,1 с") }
    }
    Text("Время от начала обрезанного ролика. Во время просмотра слышен звук исходника.",
        style = MaterialTheme.typography.bodySmall)
}

private fun timingTime(ms: Long) = "%d:%02d.%03d".format(ms / 60_000, ms / 1000 % 60, ms % 1000)

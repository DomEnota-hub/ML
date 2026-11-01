package com.domenota.medialoader.ui.screens

import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import com.domenota.medialoader.ui.components.AppSlider as Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.data.editor.VideoAudioTrackDraft
import com.domenota.medialoader.data.editor.VideoProjectDraft
import kotlin.math.roundToLong

/** Scrollable audio-control surface, subordinate to the main video timeline. */
@Composable
internal fun VideoAudioTracksPanel(
    project: VideoProjectDraft?,
    selectedTrackId: String?,
    removeOriginalAudio: Boolean,
    enabled: Boolean,
    onRemoveOriginalAudio: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onSelect: (String) -> Unit,
    onUpdate: (String, (VideoAudioTrackDraft) -> VideoAudioTrackDraft) -> Unit,
    onDelete: (String) -> Unit,
    onJumpTo: (Long) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text("Исходный звук видео", style = MaterialTheme.typography.titleSmall)
            Text(if (removeOriginalAudio) "Выключен (музыка остаётся)"
                else "Сохранить и смешать с музыкой",
                style = MaterialTheme.typography.labelSmall)
        }
        Switch(checked = !removeOriginalAudio,
            onCheckedChange = { onRemoveOriginalAudio(!it) }, enabled = enabled)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = onAdd, enabled = enabled &&
            (project?.audioTracks?.size ?: 0) < VideoProjectDraft.MAX_AUDIO_TRACKS) {
            Text("+ Добавить музыку")
        }
        Text("${project?.audioTracks?.size ?: 0}/${VideoProjectDraft.MAX_AUDIO_TRACKS}",
            style = MaterialTheme.typography.labelMedium)
    }
    val tracks = project?.audioTracks.orEmpty()
    if (tracks.isEmpty()) {
        Text("Добавь аудио, чтобы наложить музыку на видео.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        return
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        tracks.forEachIndexed { index, track ->
            FilterChip(selected = track.id == selectedTrackId,
                enabled = enabled,
                onClick = { onSelect(track.id) },
                label = { Text("Дорожка ${index + 1}", fontSize = 11.sp) })
        }
    }
    val selected = tracks.firstOrNull { it.id == selectedTrackId } ?: tracks.first()
    val id = selected.id
    Text(Uri.parse(selected.sourceUri).lastPathSegment?.takeLast(48) ?: "Аудиофайл",
        maxLines = 1, style = MaterialTheme.typography.labelSmall)
    VideoAudioWaveform(selected.sourceUri)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Громкость: ${(selected.volume * 100).toInt()} %",
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onDelete(id) }, enabled = enabled) { Text("Удалить") }
    }
    Slider(value = selected.volume,
        onValueChange = { volume -> onUpdate(id) { it.copy(volume = volume) } },
        valueRange = 0f..1f, enabled = enabled)
    Text("Начало в проекте: ${selected.atMs / 1000f} с",
        style = MaterialTheme.typography.bodySmall)
    val endOfProject = (project?.durationMs ?: 1L).coerceAtLeast(1L)
    Slider(value = selected.atMs.coerceIn(0L, endOfProject - 1L).toFloat(),
        onValueChange = { value ->
            onUpdate(id) { it.copy(atMs = value.roundToLong()
                .coerceIn(0L, endOfProject - 1L)) }
        }, valueRange = 0f..endOfProject.toFloat(), enabled = enabled)
    TextButton(onClick = { onJumpTo(selected.atMs) }, enabled = enabled) {
        Text("Перейти к началу дорожки")
    }
    Text("Обрезка · начало ${selected.inMs / 1000f} с, " +
        "конец ${selected.outMs / 1000f} с", style = MaterialTheme.typography.bodySmall)
    Text("Начало источника", style = MaterialTheme.typography.labelSmall)
    Slider(value = selected.inMs.toFloat(),
        onValueChange = { v ->
            val next = v.roundToLong().coerceIn(0L, selected.outMs - 100L)
            val length = selected.outMs - next
            onUpdate(id) {
                it.copy(inMs = next,
                    fadeInMs = it.fadeInMs.coerceAtMost(length),
                    fadeOutMs = it.fadeOutMs.coerceAtMost(
                        (length - it.fadeInMs.coerceAtMost(length)).coerceAtLeast(0L)))
            }
        }, valueRange = 0f..(selected.outMs - 100L).coerceAtLeast(0L).toFloat(),
        enabled = enabled && selected.outMs >= 100L)
    Text("Конец источника", style = MaterialTheme.typography.labelSmall)
    Slider(value = selected.outMs.toFloat(),
        onValueChange = { v ->
            val next = v.roundToLong().coerceIn(selected.inMs + 100L, selected.sourceDurationMs)
            val length = next - selected.inMs
            onUpdate(id) {
                it.copy(outMs = next,
                    fadeInMs = it.fadeInMs.coerceAtMost(length),
                    fadeOutMs = it.fadeOutMs.coerceAtMost(
                        (length - it.fadeInMs.coerceAtMost(length)).coerceAtLeast(0L)))
            }
        }, valueRange = (selected.inMs + 100L).coerceAtMost(
            selected.sourceDurationMs).toFloat()..selected.sourceDurationMs.toFloat(),
        enabled = enabled && selected.sourceDurationMs >= selected.inMs + 100L)
    Text("Плавное появление: ${selected.fadeInMs / 1000f} с",
        style = MaterialTheme.typography.bodySmall)
    val maxFadeIn = minOf(5000L, selected.durationMs - selected.fadeOutMs)
        .coerceAtLeast(0L)
    Slider(value = selected.fadeInMs.coerceAtMost(maxFadeIn).toFloat(),
        onValueChange = { v ->
            onUpdate(id) { it.copy(fadeInMs = v.roundToLong().coerceIn(0L, maxFadeIn)) }
        }, valueRange = 0f..maxFadeIn.coerceAtLeast(1L).toFloat(),
        enabled = enabled && maxFadeIn > 0L)
    Text("Плавное затухание: ${selected.fadeOutMs / 1000f} с",
        style = MaterialTheme.typography.bodySmall)
    val maxFadeOut = minOf(5000L, selected.durationMs - selected.fadeInMs)
        .coerceAtLeast(0L)
    Slider(value = selected.fadeOutMs.coerceAtMost(maxFadeOut).toFloat(),
        onValueChange = { v ->
            onUpdate(id) { it.copy(fadeOutMs = v.roundToLong().coerceIn(0L, maxFadeOut)) }
        }, valueRange = 0f..maxFadeOut.coerceAtLeast(1L).toFloat(),
        enabled = enabled && maxFadeOut > 0L)
}

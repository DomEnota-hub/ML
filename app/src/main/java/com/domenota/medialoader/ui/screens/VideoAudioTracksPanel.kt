package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import com.domenota.medialoader.ui.components.EditorSlider as Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.editor.VideoAudioTrackDraft
import com.domenota.medialoader.data.editor.VideoProjectDraft

/** Music actions; range/position editing belongs to the timeline, not numeric sliders. */
@Composable
internal fun VideoAudioTracksPanel(
    project: VideoProjectDraft?, selectedTrackId: String?, removeOriginalAudio: Boolean,
    enabled: Boolean, onRemoveOriginalAudio: (Boolean) -> Unit, onAdd: () -> Unit,
    onSelect: (String) -> Unit, onUpdate: (String, (VideoAudioTrackDraft) -> VideoAudioTrackDraft) -> Unit,
    onDelete: (String) -> Unit, onJumpTo: (Long) -> Unit,
) {
    var volumeOpen by remember { mutableStateOf(false) }
    val selected = project?.audioTracks?.firstOrNull { it.id == selectedTrackId }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onAdd, modifier = Modifier.weight(1f), enabled = enabled &&
            (project?.audioTracks?.size ?: 0) < VideoProjectDraft.MAX_AUDIO_TRACKS) {
            Icon(Icons.Rounded.MusicNote, null)
            Text("Добавить\nмузыку", Modifier.padding(start = 6.dp))
        }
        IconButton(onClick = { onRemoveOriginalAudio(!removeOriginalAudio) }, enabled = enabled) {
            Icon(if (removeOriginalAudio) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                "Исходный звук видео")
        }
        if (selected != null) {
            IconButton(onClick = { volumeOpen = true }, enabled = enabled) {
                Icon(Icons.Rounded.Tune, "Громкость дорожки")
            }
            IconButton(onClick = { onDelete(selected.id) }, enabled = enabled) {
                Icon(Icons.Rounded.Delete, "Удалить дорожку")
            }
        }
    }
    if (volumeOpen && selected != null) AlertDialog(
        onDismissRequest = { volumeOpen = false },
        title = { Text("Громкость") },
        text = { Column {
            Text("${(selected.volume * 100).toInt()} %")
            Slider(selected.volume, { value -> onUpdate(selected.id) { it.copy(volume = value) } },
                valueRange = 0f..1f, enabled = enabled)
        } },
        confirmButton = { TextButton(onClick = { volumeOpen = false }) { Text("Готово") } })
}

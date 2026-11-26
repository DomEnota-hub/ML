package com.domenota.medialoader.ui.screens.music

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.domenota.medialoader.data.playback.AudioQueuePlayback
import com.domenota.medialoader.data.playback.PrivatePlaybackPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SaveQueuePlaylistAction(
    player: Player, hasItems: Boolean, blocked: Set<String>,
    onSave: suspend (String, List<MediaItem>) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val latestBlocked by rememberUpdatedState(blocked)
    val latestSave by rememberUpdatedState(onSave)
    TextButton(onClick = { name = ""; message = null; open = true },
        enabled = hasItems && !busy, modifier = Modifier.padding(horizontal = 12.dp)) {
        Icon(Icons.Rounded.PlaylistAdd, null)
        Text("Сохранить очередь в плейлист", Modifier.padding(start = 8.dp))
    }
    if (!open) message?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
    if (open) AlertDialog(
        onDismissRequest = { if (!busy) open = false },
        title = { Text("Сохранить очередь") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(),
                    label = { Text("Название плейлиста") }, singleLine = true, enabled = !busy)
                Text("Текущий порядок воспроизведения. Повторяющиеся треки сохраняются один раз.",
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !busy, onClick = {
                busy = true
                message = null
                scope.launch {
                    try {
                        val snapshot = AudioQueuePlayback.playlistSnapshot(player)
                        check(snapshot.none { PrivatePlaybackPolicy.blocked(it, false, latestBlocked) })
                        latestSave(name.trim(), snapshot)
                        open = false
                        message = "Плейлист сохранён"
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        message = "Не удалось сохранить очередь. Проверьте доступ к трекам."
                    } finally { busy = false }
                }
            }) { Text(if (busy) "Сохранение…" else "Сохранить") }
        },
        dismissButton = { TextButton(onClick = { open = false }, enabled = !busy) { Text("Отмена") } },
    )
}

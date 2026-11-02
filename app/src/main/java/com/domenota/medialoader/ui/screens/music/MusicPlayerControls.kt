package com.domenota.medialoader.ui.screens.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.ui.components.AppSlider
import com.domenota.medialoader.ui.components.DownloadThumbnail
import com.domenota.medialoader.ui.model.toUi
import com.domenota.medialoader.ui.screens.audioArtist
import com.domenota.medialoader.ui.screens.audioTitle

@Composable
internal fun MusicPlayerControls(
    item: DownloadEntity, player: Player?, positionMs: Long, durationMs: Long, playing: Boolean,
    shuffle: Boolean, repeatMode: Int, speed: Float,
    onBack: () -> Unit, onEqualizer: (() -> Unit)?, onQueue: () -> Unit, onFavorite: (() -> Unit)?,
    favorite: Boolean = false, onEdit: (() -> Unit)? = null,
) {
    var scrub by remember(item.id) { mutableStateOf<Float?>(null) }
    var speedScrub by remember(item.id) { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
            Text("Сейчас играет", Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            if (onEdit != null) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Rounded.Edit, contentDescription = "Редактировать")
                }
            }
        }
        Box(Modifier.padding(top = 15.dp, bottom = 22.dp).size(220.dp).clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)) {
            DownloadThumbnail(item.savedUri, item.recognitionArtworkUrl ?: item.sourceAudioArtworkUrl ?: item.previewUrl,
                item.toUi().kind, Modifier.fillMaxSize())
        }
        Text(audioTitle(item), Modifier.padding(horizontal = 17.dp), fontSize = 22.sp, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        audioArtist(item)?.let { Text(it, Modifier.padding(top = 5.dp, start = 17.dp, end = 17.dp), fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Column(Modifier.fillMaxWidth().padding(start = 27.dp, end = 27.dp, top = 25.dp, bottom = 8.dp)) {
            AppSlider(value = scrub ?: positionMs.toFloat().coerceIn(0f, durationMs.coerceAtLeast(1).toFloat()),
                onValueChange = { scrub = it }, modifier = Modifier.fillMaxWidth().height(28.dp), enabled = player != null && durationMs > 0,
                valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(), onValueChangeFinished = { scrub?.let { player?.seekTo(it.toLong()) }; scrub = null })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(time(scrub?.toLong() ?: positionMs), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(time(durationMs), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 15.dp), horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically) {
            IconButton({ player?.shuffleModeEnabled = !shuffle }, enabled = player != null) {
                Icon(Icons.Rounded.Shuffle, "Перемешать", tint = if (shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton({ player?.seekToPreviousMediaItem() }, enabled = player?.hasPreviousMediaItem() == true) { Icon(Icons.Rounded.SkipPrevious, "Предыдущий трек") }
            FilledIconButton(onClick = { player?.let { com.domenota.medialoader.data.playback.AudioQueuePlayback.toggle(it) } },
                enabled = player != null, modifier = Modifier.size(65.dp), shape = CircleShape) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Пауза" else "Воспроизвести", Modifier.size(30.dp))
            }
            IconButton({ player?.seekToNextMediaItem() }, enabled = player?.hasNextMediaItem() == true) { Icon(Icons.Rounded.SkipNext, "Следующий трек") }
            val repeatLabel = when (repeatMode) { Player.REPEAT_MODE_ALL -> "Повтор очереди"; Player.REPEAT_MODE_ONE -> "Повтор трека"; else -> "Повтор выключен" }
            IconButton({ player?.repeatMode = when (repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } }, enabled = player != null) {
                Icon(if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, repeatLabel,
                    tint = if (repeatMode == Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
            }
        }
        // Playback speed belongs to the main controls, not a hidden overflow menu.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Скорость", style = MaterialTheme.typography.labelMedium)
            AppSlider(
                value = speedScrub ?: speed.coerceIn(.75f, 2f),
                onValueChange = { next ->
                    val allowed = listOf(.75f, 1f, 1.25f, 1.5f, 2f)
                    val rounded = allowed.minByOrNull { kotlin.math.abs(it - next) } ?: 1f
                    speedScrub = rounded
                    player?.setPlaybackSpeed(rounded)
                },
                onValueChangeFinished = { speedScrub = null },
                valueRange = .75f..2f,
                modifier = Modifier.weight(1f).height(32.dp),
                enabled = player != null,
            )
            Text("${speedScrub ?: speed}×", style = MaterialTheme.typography.labelMedium)
        }
        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            IconButton(onClick = { onFavorite?.invoke() }, enabled = onFavorite != null) {
                Icon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Избранное",
                    tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = { onEqualizer?.invoke() }, enabled = onEqualizer != null) { Icon(Icons.Rounded.Equalizer, "Эквалайзер") }
            IconButton(onQueue, enabled = player != null) { Icon(Icons.Rounded.QueueMusic, "Очередь") }
        }
    }
}

private fun time(value: Long): String {
    val seconds = value.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "${seconds / 3600}:${((seconds % 3600) / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
        else "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

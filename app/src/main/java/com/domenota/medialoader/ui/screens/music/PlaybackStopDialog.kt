package com.domenota.medialoader.ui.screens.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.domenota.medialoader.data.playback.*
import com.domenota.medialoader.ui.theme.musicPlaybackAccent

@Composable
internal fun PlaybackStopDialog(player: Player?, onDismiss: () -> Unit) {
    val active by AudioPlaybackService.timerState.collectAsState()
    var criterion by remember { mutableStateOf(StopCriterion.DURATION) }
    var hours by remember { mutableStateOf("0") }; var minutes by remember { mutableStateOf("30") }; var seconds by remember { mutableStateOf("0") }
    var clockHour by remember { mutableStateOf("23") }; var clockMinute by remember { mutableStateOf("0") }
    var tracks by remember { mutableStateOf("3") }; var finish by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val playlistAvailable = player?.currentMediaItem?.mediaMetadata?.extras?.getString(AudioPlaybackMetadata.PLAYLIST_ID) != null
    val h = hours.toIntOrNull(); val m = minutes.toIntOrNull(); val s = seconds.toIntOrNull()
    val ch = clockHour.toIntOrNull(); val cm = clockMinute.toIntOrNull(); val n = tracks.toIntOrNull()
    val duration = (h ?: 0)*3600L+(m ?: 0)*60L+(s ?: 0)
    val valid = when(criterion) {
        StopCriterion.TIME -> ch != null && ch in 0..23 && cm != null && cm in 0..59
        StopCriterion.DURATION -> h != null && h in 0..23 && m != null && m in 0..59 && s != null && s in 0..59 && duration > 0
        StopCriterion.TRACKS -> n != null && n in 1..999
        StopCriterion.PLAYLIST -> playlistAvailable
        StopCriterion.QUEUE -> true
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Остановка воспроизведения") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            active?.let { state ->
                Text(when {
                    state.waitingForEnd -> "До конца текущего трека"
                    state.criterion == StopCriterion.TRACKS -> "Осталось треков: ${state.remaining}"
                    state.criterion == StopCriterion.PLAYLIST -> "До конца плейлиста"
                    state.criterion == StopCriterion.QUEUE -> "До конца очереди"
                    else -> "Осталось: %02d:%02d:%02d".format(state.remaining/3600,state.remaining/60%60,state.remaining%60)
                },color = musicPlaybackAccent)
                OutlinedButton({ AudioPlaybackService.cancelStopTimer() }, Modifier.fillMaxWidth()) { Text("Отменить таймер") }
                HorizontalDivider()
            }
            listOf(StopCriterion.TIME to "В указанное время", StopCriterion.DURATION to "Через указанное время",
                StopCriterion.TRACKS to "После окончания треков", StopCriterion.PLAYLIST to "После окончания плейлиста",
                StopCriterion.QUEUE to "После окончания очереди").forEach { (mode,title) ->
                val enabled = mode != StopCriterion.PLAYLIST || playlistAvailable
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = criterion == mode,onClick = { criterion = mode; error = null },enabled = enabled)
                    TextButton(onClick = { criterion = mode; error = null },enabled = enabled) { Text(title) }
                }
            }
            if (!playlistAvailable) Text("Для остановки по плейлисту запустите его из библиотеки.", style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (criterion) {
                StopCriterion.TIME -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimerNumber(clockHour,{ clockHour = it },"Часы",Modifier.weight(1f))
                    TimerNumber(clockMinute,{ clockMinute = it },"Минуты",Modifier.weight(1f))
                }
                StopCriterion.DURATION -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TimerNumber(hours,{ hours = it },"Часы",Modifier.weight(1f))
                        TimerNumber(minutes,{ minutes = it },"Мин.",Modifier.weight(1f))
                        TimerNumber(seconds,{ seconds = it },"Сек.",Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf(15,30,45,60).forEach { v -> TextButton({ hours = (v/60).toString(); minutes = (v%60).toString(); seconds = "0" },contentPadding = PaddingValues(4.dp)) { Text("$v мин") } }
                    }
                }
                StopCriterion.TRACKS -> TimerNumber(tracks,{ tracks = it },"Количество треков",Modifier.fillMaxWidth(),3)
                else -> Unit
            }
            if (criterion == StopCriterion.TIME || criterion == StopCriterion.DURATION) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Дождаться окончания текущего трека",Modifier.weight(1f)); Switch(finish,{ finish = it })
            }
            error?.let { Text(it,color = MaterialTheme.colorScheme.error) }
        }
    }, dismissButton = { TextButton(onDismiss) { Text("Закрыть") } },
        confirmButton = { TextButton(enabled = valid && player != null,onClick = {
            val ok = AudioPlaybackService.startStopTimer(StopRequest(criterion,duration,ch ?: 0,cm ?: 0,n ?: 1,finish))
            if (ok) onDismiss() else error = "Не удалось запустить таймер. Проверьте воспроизведение."
        }) { Text("Запустить") } })
}
@Composable
private fun TimerNumber(value: String, change: (String) -> Unit, label: String, modifier: Modifier, maxLength: Int = 2) {
    OutlinedTextField(value,{ if (it.length <= maxLength && it.all(Char::isDigit)) change(it) },modifier,
        label = { Text(label) },singleLine = true,keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

package com.domenota.medialoader.ui.screens.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.playback.*
import com.domenota.medialoader.ui.theme.musicPlaybackAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicStopTimerAction(enabled: Boolean) {
    val flow = remember(enabled) { AudioPlaybackService.timerState() }
    val status by flow.collectAsState()
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, enabled = enabled) {
        Icon(Icons.Rounded.Timer,"Остановка воспроизведения",tint = if (status.request != null) musicPlaybackAccent else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!open) return
    var criterion by remember { mutableStateOf(status.request?.criterion ?: MusicStopCriterion.AFTER_TIME) }
    var hours by remember { mutableStateOf("0") }
    var minutes by remember { mutableStateOf("30") }
    var seconds by remember { mutableStateOf("0") }
    var clockHour by remember { mutableStateOf("23") }
    var clockMinute by remember { mutableStateOf("00") }
    var tracks by remember { mutableStateOf("3") }
    var finish by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val request = when (criterion) {
        MusicStopCriterion.AT_TIME -> {
            val h = clockHour.toLongOrNull(); val m = clockMinute.toLongOrNull()
            MusicStopRequest(criterion,if (h != null && h in 0..23 && m != null && m in 0..59) h*60+m else -1,finish)
        }
        MusicStopCriterion.AFTER_TIME -> {
            val h = hours.toLongOrNull(); val m = minutes.toLongOrNull(); val s = seconds.toLongOrNull()
            MusicStopRequest(criterion,if (h != null && h in 0..23 && m != null && m in 0..59 && s != null && s in 0..59) h*3600+m*60+s else -1,finish)
        }
        MusicStopCriterion.TRACKS -> MusicStopRequest(criterion,tracks.toLongOrNull() ?: 0)
        else -> MusicStopRequest(criterion)
    }
    ModalBottomSheet(onDismissRequest = { open = false }) {
        com.domenota.medialoader.ui.theme.TransparentWindowNavigation()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Остановка воспроизведения", style = MaterialTheme.typography.titleLarge)
            if (status.request != null) {
                val label = when (status.request?.criterion) {
                    MusicStopCriterion.AT_TIME, MusicStopCriterion.AFTER_TIME -> if (status.waiting) "До конца текущего трека" else "Осталось %02d:%02d:%02d".format(status.remaining/3600,status.remaining/60%60,status.remaining%60)
                    MusicStopCriterion.TRACKS -> "Осталось треков: ${status.remaining}"
                    else -> status.request?.criterion?.label.orEmpty()
                }
                Text(label,color = musicPlaybackAccent)
                OutlinedButton({ AudioPlaybackService.cancelStopTimer() },Modifier.fillMaxWidth()) { Text("Отменить таймер") }
            }
            MusicStopCriterion.entries.forEach { item ->
                val available = item != MusicStopCriterion.PLAYLIST || status.playlistAvailable
                Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(criterion == item,{ criterion = item },enabled = available)
                    Column(Modifier.weight(1f)) {
                        Text(item.label,color = if (!available) MaterialTheme.colorScheme.onSurfaceVariant else if (criterion==item) musicPlaybackAccent else MaterialTheme.colorScheme.onSurface)
                        if (!available) Text("Сначала запустите трек из плейлиста",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            when (criterion) {
                MusicStopCriterion.AT_TIME -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimerNumber(clockHour,{clockHour=it},"Часы",Modifier.weight(1f),2)
                    TimerNumber(clockMinute,{clockMinute=it},"Минуты",Modifier.weight(1f),2)
                }
                MusicStopCriterion.AFTER_TIME -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TimerNumber(hours,{hours=it},"Часы",Modifier.weight(1f),2)
                        TimerNumber(minutes,{minutes=it},"Мин.",Modifier.weight(1f),2)
                        TimerNumber(seconds,{seconds=it},"Сек.",Modifier.weight(1f),2)
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf(15,30,45,60).forEach { n -> TextButton({ hours=(n/60).toString(); minutes=(n%60).toString(); seconds="0" }) { Text("$n мин") } }
                    }
                }
                MusicStopCriterion.TRACKS -> TimerNumber(tracks,{tracks=it},"Количество треков",Modifier.fillMaxWidth(),3)
                else -> Text("Повтор временно отключается до завершения или отмены таймера.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (criterion == MusicStopCriterion.AT_TIME || criterion == MusicStopCriterion.AFTER_TIME) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Дождаться окончания текущего трека",Modifier.weight(1f))
                    Switch(finish,{finish=it})
                }
            }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            Button(onClick = {
                if (AudioPlaybackService.startStopTimer(request)) open=false
                else error="Не удалось установить условие. Проверьте текущую очередь."
            }, enabled=MusicStopTimerPolicy.valid(request) && (criterion != MusicStopCriterion.PLAYLIST || status.playlistAvailable),modifier=Modifier.fillMaxWidth()) {
                Text(if (status.request == null) "Запустить" else "Изменить таймер")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
@Composable
private fun TimerNumber(value:String,change:(String)->Unit,label:String,modifier:Modifier,digits:Int) {
    OutlinedTextField(value,{ if (it.length<=digits && it.all(Char::isDigit)) change(it) },modifier,
        label={ Text(label) },singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
}

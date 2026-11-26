package com.domenota.medialoader.ui.screens.music

import com.domenota.medialoader.ui.components.NoticeSheet as ModalBottomSheet

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
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
    var hours by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("") }
    var seconds by remember { mutableStateOf("") }
    var clockHour by remember { mutableStateOf("23") }
    var clockMinute by remember { mutableStateOf("00") }
    var finish by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val request = when (criterion) {
        MusicStopCriterion.AT_TIME -> {
            val h = clockHour.toLongOrNull(); val m = clockMinute.toLongOrNull()
            MusicStopRequest(criterion,if (h != null && h in 0..23 && m != null && m in 0..59) h*60+m else -1,finish)
        }
        MusicStopCriterion.AFTER_TIME -> {
            MusicStopRequest(criterion,MusicStopTimerPolicy.durationSeconds(hours,minutes,seconds),finish)
        }
        else -> MusicStopRequest(criterion)
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { open = false }, sheetState = sheetState) {
        com.domenota.medialoader.ui.theme.TransparentWindowNavigation()
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Остановка воспроизведения", style = MaterialTheme.typography.titleLarge)
            if (status.request != null) {
                val label = when (status.request?.criterion) {
                    MusicStopCriterion.AT_TIME, MusicStopCriterion.AFTER_TIME -> if (status.waiting) "До конца текущего трека" else "Осталось %02d:%02d:%02d".format(status.remaining/3600,status.remaining/60%60,status.remaining%60)
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
                        if (!available) Text(status.playlistUnavailableReason ?: "Нет активного плейлиста",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        listOf(15,30,45,60).forEach { n -> TextButton({ hours=(n/60).takeIf { it>0 }?.toString().orEmpty(); minutes=(n%60).takeIf { it>0 }?.toString().orEmpty(); seconds="" }) { Text("$n мин") } }
                    }
                }
                MusicStopCriterion.PLAYLIST -> Unit
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
                else error="Условие остановки недоступно"
            }, enabled=MusicStopTimerPolicy.valid(request) && (criterion != MusicStopCriterion.PLAYLIST || status.playlistAvailable),modifier=Modifier.fillMaxWidth()) {
                Text(if (status.request == null) "Запустить" else "Изменить таймер")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TimerNumber(value:String,change:(String)->Unit,label:String,modifier:Modifier,digits:Int) {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant
    // Material3 hides a standard placeholder while an empty labelled field is unfocused.
    // Keep the floating label and gray zero visible without putting zero in editable state.
    val zeroHint = remember(hintColor) {
        VisualTransformation { input ->
            if (input.isNotEmpty()) TransformedText(input, OffsetMapping.Identity)
            else TransformedText(buildAnnotatedString { withStyle(SpanStyle(color = hintColor)) { append("0") } },
                object : OffsetMapping {
                    override fun originalToTransformed(offset: Int): Int = 0
                    override fun transformedToOriginal(offset: Int): Int = 0
                })
        }
    }
    OutlinedTextField(value,{ if (it.length<=digits && it.all(Char::isDigit)) change(it) },modifier
        .bringIntoViewRequester(requester).onFocusChanged { focus ->
            if (focus.isFocused) scope.launch { delay(350); requester.bringIntoView() }
        },
        label={ Text(label) }, visualTransformation=zeroHint,
        shape=RoundedCornerShape(16.dp),
        colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=musicPlaybackAccent),
        singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
}

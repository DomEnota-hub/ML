package com.domenota.medialoader.ui.screens.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.playback.ResolvedResumeQueue
import com.domenota.medialoader.data.playback.ResumeQueueStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.domenota.medialoader.ui.screens.audioTitle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun ResumeMusicCard(count:Int,onOpen:()->Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal=17.dp,vertical=5.dp).clickable(onClick=onOpen)
        .testTag("resume-music-card"),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(13.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(13.dp)){
            Icon(Icons.Rounded.PlayCircle,null,Modifier.size(48.dp),tint=MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)){
                Text("Продолжить слушать",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                Text("Сохранено сеансов: $count",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight,null)
        }
    }
}
private fun position(ms:Long):String {
    val sec=(ms/1000).coerceAtLeast(0)
    return if(sec>=3600) "%d:%02d:%02d".format(sec/3600,sec/60%60,sec%60) else "%d:%02d".format(sec/60,sec%60)
}
@Composable
internal fun ResumeMusicScreen(sessions:List<ResolvedResumeQueue>,rows:Map<String,DownloadEntity>,
    playlistNames:Map<String,String>,loading:Boolean,error:Boolean,onBack:()->Unit,onResume:(ResolvedResumeQueue)->Unit,store:ResumeQueueStore?=null) {
    val scope=rememberCoroutineScope()
    var editingId by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var deletingId by remember { mutableStateOf<String?>(null) }
    var clearAll by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    fun mutate(action:suspend ()->Unit) { scope.launch {
        busy=true
        try {action();selected=emptySet();problem=null}
        catch(e:CancellationException){throw e}
        catch(_:Exception){problem="Не удалось сохранить очередь. Откройте её заново и повторите."}
        finally {busy=false}
    } }
    val editing=sessions.firstOrNull { it.session.id==editingId }
    androidx.activity.compose.BackHandler(editingId!=null) {editingId=null;selected=emptySet()}
    var confirmation by remember { mutableStateOf<ResolvedResumeQueue?>(null) }
    LazyColumn(Modifier.fillMaxSize().testTag("resume-music"),contentPadding=PaddingValues(bottom=12.dp)){
        item(key="header"){
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically){
                IconButton({if(editingId!=null){editingId=null;selected=emptySet()}else onBack()}){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")}
                Text(if(editingId==null)"Продолжить слушать" else "Состав очереди",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                if(store!=null && editingId==null)IconButton({clearAll=true},enabled=sessions.isNotEmpty()&&!busy){Icon(Icons.Rounded.DeleteSweep,"Очистить все сеансы")}
            }
            Text("Вернись к очереди, треку и позиции, на которой остановился. Сохраняются последние 8 сеансов.",
                Modifier.padding(horizontal=17.dp,vertical=8.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
            problem?.let { Text(it,Modifier.padding(17.dp),color=MaterialTheme.colorScheme.error) }
            if(loading||busy)LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal=17.dp))
            if(error)Text("Не удалось прочитать сохранённые сеансы",Modifier.padding(17.dp),color=MaterialTheme.colorScheme.error)
            else if(!loading&&sessions.isEmpty())Text("Здесь появятся незавершённые сеансы воспроизведения.",Modifier.padding(17.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(editing!=null) {
            item(key="queue-actions") {
                Column(Modifier.padding(horizontal=17.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Изменяется только сохранённый сеанс. Текущая музыка продолжает играть.",style=MaterialTheme.typography.bodySmall)
                    TextButton({store?.let { mutate { it.edit(editing.session,selected,editing) } }},enabled=selected.isNotEmpty()&&!busy){Text("Удалить выбранные (${selected.size})")}
                    TextButton({store?.let { target->mutate { target.edit(editing.session,editing.ids.indices.filter { editing.ids[it]==null }.map { editing.session.occurrenceIds[it] }.toSet(),editing) } }},enabled=editing.missing>0&&!busy){Text("Удалить недоступные (${editing.missing})")}
                    TextButton({deletingId=editing.session.id},enabled=!busy){Text("Очистить эту очередь",color=MaterialTheme.colorScheme.error)}
                }
            }
            items(editing.session.entries.indices.toList(),key={editing.session.occurrenceIds[it]}) { index ->
                val occurrence=editing.session.occurrenceIds[index]
                val track=editing.ids.getOrNull(index)?.let(rows::get)
                Row(Modifier.fillMaxWidth().clickable(enabled=!busy){selected=if(occurrence in selected)selected-occurrence else selected+occurrence}.padding(horizontal=17.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(occurrence in selected,{checked->selected=if(checked)selected+occurrence else selected-occurrence},enabled=!busy)
                    Column(Modifier.weight(1f)) {
                        Text("${index+1}. ${track?.let(::audioTitle) ?: "Трек недоступен"}")
                        if(index==editing.session.index)Text("Сохранённая позиция: ${position(editing.session.positionMs)}",style=MaterialTheme.typography.bodySmall)
                    }
                    IconButton({store?.let { target->mutate { target.edit(editing.session,setOf(occurrence),editing) } }},enabled=!busy){Icon(Icons.Rounded.Close,"Удалить вхождение ${index+1}")}
                }
            }
        }
        items(if(editingId==null)sessions else emptyList(),key={it.session.id}){resolved->
            val saved=resolved.session
            val track=resolved.currentId?.let(rows::get)
            val title=if(saved.playlistId!=null)playlistNames[saved.playlistId]?:"Сохранённая очередь" else "Сохранённая очередь"
            val date=Instant.ofEpochMilli(saved.updatedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))
            Surface(Modifier.fillMaxWidth().padding(horizontal=17.dp,vertical=6.dp),
                shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                    Text(track?.let(::audioTitle)?:"Трек недоступен",style=MaterialTheme.typography.bodyLarge)
                    Text("Трек ${saved.index+1} из ${saved.entries.size} · остановился на ${position(saved.positionMs)}",
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(saved.durationMs>0)LinearProgressIndicator(progress={ (saved.positionMs.toDouble()/saved.durationMs).coerceIn(0.0,1.0).toFloat() },modifier=Modifier.fillMaxWidth())
                    Text(date,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(resolved.missing>0)Text("Недоступно: ${resolved.missing} из ${saved.entries.size}",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                    if(store!=null)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton({editingId=saved.id;selected=emptySet()},enabled=!busy){Text("Состав очереди")}
                        TextButton({deletingId=saved.id},enabled=!busy){Text("Удалить сеанс")}
                    }
                    Button(onClick={if(resolved.missing>0)confirmation=resolved else onResume(resolved)},enabled=track!=null&&!loading,modifier=Modifier.fillMaxWidth()){
                        Icon(Icons.Rounded.PlayArrow,null);Text("Продолжить")
                    }
                }
            }
        }
    }
    if(clearAll || deletingId!=null)AlertDialog(onDismissRequest={clearAll=false;deletingId=null},
        title={Text(if(clearAll)"Очистить все сохранённые сеансы?" else "Удалить сохранённую очередь?")},
        text={Text("Файлы и текущее воспроизведение сохранятся.")},
        confirmButton={TextButton({val all=clearAll;val id=deletingId;clearAll=false;deletingId=null
            store?.let { target->mutate {if(all)target.clearSaved() else if(id!=null)target.deleteSaved(id)} }
            if(all || editingId==id)editingId=null
        }){Text("Удалить",color=MaterialTheme.colorScheme.error)}},
        dismissButton={TextButton({clearAll=false;deletingId=null}){Text("Отмена")}})
    confirmation?.let { selected ->
        AlertDialog(onDismissRequest={confirmation=null},title={Text("Часть очереди недоступна")},
            text={Text("Пропустить ${selected.missing} недоступных треков и продолжить с прежней позиции? Сохранённая очередь будет восстановлена только из доступных файлов.")},
            confirmButton={TextButton({confirmation=null;onResume(selected)}){Text("Продолжить с доступными")}},
            dismissButton={TextButton({confirmation=null}){Text("Отмена")}})
    }
}

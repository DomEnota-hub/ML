package com.domenota.medialoader.ui.screens.music

import com.domenota.medialoader.ui.model.displayAudioTitle
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
import androidx.compose.ui.text.style.TextOverflow
import com.domenota.medialoader.ui.screens.audioArtist
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.playback.ResolvedResumeQueue
import com.domenota.medialoader.data.playback.ResumeAvailability
import com.domenota.medialoader.data.playback.ResumeQueueStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.domenota.medialoader.ui.screens.audioTitle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun ResumeMusicCard(count:Int,onOpen:()->Unit) {
    MusicOverviewCard("Продолжить слушать","Сохранено сеансов: $count",Icons.Rounded.PlayCircle,"resume-music-card",onOpen)
}
private fun position(ms:Long):String {
    val sec=(ms/1000).coerceAtLeast(0)
    return if(sec>=3600) "%d:%02d:%02d".format(sec/3600,sec/60%60,sec%60) else "%d:%02d".format(sec/60,sec%60)
}
@Composable
internal fun ResumeMusicScreen(sessions:List<ResolvedResumeQueue>,rows:Map<String,DownloadEntity>,
    playlistNames:Map<String,String>,loading:Boolean,error:Boolean,onBack:()->Unit,onResume:(ResolvedResumeQueue)->Unit,
    store:ResumeQueueStore?=null,onRecheck:(()->Unit)?=null) {
    val scope=rememberCoroutineScope()
    var editingId by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var deletingId by remember { mutableStateOf<String?>(null) }
    var clearAll by remember { mutableStateOf(false) }
    var removeMissing by remember { mutableStateOf<ResolvedResumeQueue?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var query by remember(editingId){mutableStateOf("")}
    var missingOnly by remember(editingId){mutableStateOf(false)}
    var menuOpen by remember(editingId){mutableStateOf(false)}
    fun leaveQueue(){editingId=null;selected=emptySet();removeMissing=null}
    fun mutate(action:suspend ()->Unit) {
        if(busy)return
        busy=true
        scope.launch {
            try {action();selected=emptySet();problem=null}
            catch(e:CancellationException){throw e}
            catch(_:Exception){problem="Не удалось сохранить очередь. Открой очередь заново и повтори."}
            finally {busy=false}
        }
    }
    val editing=sessions.firstOrNull { it.session.id==editingId }
    val canEdit=store!=null&&!loading&&!busy&&!error
    androidx.activity.compose.BackHandler(editingId!=null) {leaveQueue()}
    var confirmation by remember { mutableStateOf<ResolvedResumeQueue?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton({if(editingId!=null)leaveQueue()else onBack()}){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")}
            Column(Modifier.weight(1f)){
                Text(if(editingId==null)"Продолжить слушать" else "Состав очереди",style=MaterialTheme.typography.titleLarge)
                editing?.let{saved->
                    Text("${saved.session.entries.size} треков · "+Instant.ofEpochMilli(saved.session.updatedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM HH:mm")),
                        style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if(store!=null && editingId==null)IconButton({clearAll=true},enabled=sessions.isNotEmpty()&&canEdit){Icon(Icons.Rounded.DeleteSweep,"Очистить все сеансы")}
            if(editing!=null&&store!=null)Box{
                IconButton({menuOpen=true},enabled=!busy){Icon(Icons.Rounded.MoreVert,"Действия с очередью")}
                DropdownMenu(expanded=menuOpen,onDismissRequest={menuOpen=false}){
                    if(onRecheck!=null)DropdownMenuItem(text={Text("Перепроверить доступность")},leadingIcon={Icon(Icons.Rounded.Refresh,null)},
                        enabled=!loading&&!busy,onClick={menuOpen=false;problem=null;onRecheck()})
                    DropdownMenuItem(text={Text("Убрать недоступные (${editing.missing})")},leadingIcon={Icon(Icons.Rounded.RemoveCircleOutline,null)},
                        enabled=canEdit&&editing.missing>0,onClick={menuOpen=false;removeMissing=editing})
                    DropdownMenuItem(text={Text("Очистить очередь",color=MaterialTheme.colorScheme.error)},leadingIcon={Icon(Icons.Rounded.DeleteOutline,null,tint=MaterialTheme.colorScheme.error)},
                        enabled=canEdit,onClick={menuOpen=false;deletingId=editing.session.id})
                }
            }
        }
        problem?.let { Text(it,Modifier.padding(horizontal=17.dp,vertical=4.dp),color=MaterialTheme.colorScheme.error) }
        if(loading||busy)LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal=17.dp))
        if(error)Text("Не удалось проверить сохранённые сеансы",Modifier.padding(horizontal=17.dp,vertical=4.dp),color=MaterialTheme.colorScheme.error)
        if(editing!=null){
            val saved=editing.session
            val selectedHere=remember(selected,saved.occurrenceIds){selected.intersect(saved.occurrenceIds.toSet())}
            val visible=remember(editing,rows,missingOnly,query){
                val q=query.trim()
                saved.entries.indices.filter{index->
                    val id=editing.ids.getOrNull(index)
                    val track=id?.let(rows::get)
                    (!missingOnly||id==null)&&(q.isBlank()||
                        (track?.let{audioTitle(it).contains(q,true)||audioArtist(it)?.contains(q,true)==true} ?: false)||
                        (id==null&&"Трек недоступен".contains(q,true)))
                }
            }
            val current=editing.currentId?.let(rows::get)
            Surface(Modifier.fillMaxWidth().padding(horizontal=17.dp,vertical=4.dp),shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant){
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        Icon(Icons.Rounded.Bookmark,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(18.dp))
                        Text("Сохранённая позиция",fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.bodyMedium)
                    }
                    Text(if(current!=null)displayAudioTitle(current) else if(loading)"Проверка доступности…" else "Трек недоступен",
                        maxLines=2,overflow=TextOverflow.Ellipsis,color=if(current==null&&!loading)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Text("Трек ${saved.index+1} из ${saved.entries.size} · ${position(saved.positionMs)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(saved.durationMs>0)LinearProgressIndicator(progress={(saved.positionMs.toDouble()/saved.durationMs).coerceIn(0.0,1.0).toFloat()},
                        modifier=Modifier.fillMaxWidth().height(4.dp),drawStopIndicator={})
                }
            }
            OutlinedTextField(value=query,onValueChange={query=it},singleLine=true,placeholder={Text("Поиск по очереди")},
                leadingIcon={Icon(Icons.Rounded.Search,null)},trailingIcon={if(query.isNotEmpty())IconButton({query=""}){Icon(Icons.Rounded.Close,"Очистить поиск")}},
                modifier=Modifier.fillMaxWidth().padding(horizontal=17.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal=17.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                FilterChip(selected=!missingOnly,onClick={missingOnly=false},label={Text("Все · ${saved.entries.size}")},modifier=Modifier.weight(1f))
                FilterChip(selected=missingOnly,onClick={missingOnly=true},label={Text("Недоступные · ${editing.missing}")},modifier=Modifier.weight(1f))
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("resume-music"),contentPadding=PaddingValues(horizontal=17.dp,vertical=4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                if(visible.isEmpty())item{Text("Нет совпадений",Modifier.padding(12.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
                items(visible,key={saved.occurrenceIds[it]}){index->
                    val occurrence=saved.occurrenceIds[index]
                    val track=editing.ids.getOrNull(index)?.let(rows::get)
                    val missing=editing.ids.getOrNull(index)==null
                    val marked=index==saved.index
                    Surface(Modifier.fillMaxWidth().testTag("resume-entry-"+occurrence),shape=RoundedCornerShape(12.dp),
                        color=if(marked)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant){
                        Row(Modifier.fillMaxWidth().clickable(enabled=canEdit){selected=if(occurrence in selected)selected-occurrence else selected+occurrence}
                            .padding(horizontal=6.dp,vertical=3.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
                            Checkbox(occurrence in selected,{checked->selected=if(checked)selected+occurrence else selected-occurrence},enabled=canEdit)
                            Text((index+1).toString(),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
                                Text(track?.let(::audioTitle)?:if(loading)"Проверка доступности…" else if(missing)"Трек недоступен" else "Данные трека уточняются",
                                    color=if(missing&&!loading)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                    maxLines=2,overflow=TextOverflow.Ellipsis)
                                if(marked)Text("Сохранённая позиция: ${position(saved.positionMs)}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                                if(missing&&!loading)Text(when(editing.availability.getOrNull(index)){
                                    ResumeAvailability.AMBIGUOUS -> "Несколько возможных совпадений"
                                    ResumeAvailability.CONFLICTING -> "Идентификаторы противоречат друг другу"
                                    else -> "Связь с файлом не подтверждена"
                                },style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton({store?.let{target->mutate{target.edit(saved,setOf(occurrence),editing)}}},enabled=canEdit){
                                Icon(Icons.Rounded.Close,"Убрать вхождение ${index+1}",modifier=Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
            Surface(Modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.surfaceContainer,tonalElevation=2.dp){
                Column(Modifier.fillMaxWidth().padding(horizontal=17.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                        val shownIds=remember(visible,saved.occurrenceIds){visible.map{saved.occurrenceIds[it]}.toSet()}
                        val allShown=shownIds.isNotEmpty()&&selectedHere.containsAll(shownIds)
                        TextButton({selected=if(allShown)selected-shownIds else selected+shownIds},enabled=canEdit&&shownIds.isNotEmpty(),contentPadding=PaddingValues(horizontal=4.dp)){
                            Text(if(allShown)"Снять выделение показанных" else "Выбрать показанные")
                        }
                        Spacer(Modifier.weight(1f))
                        Text("Выбрано: ${selectedHere.size}",style=MaterialTheme.typography.labelSmall)
                    }
                    Button({val ids=selectedHere;store?.let{target->mutate{target.edit(saved,ids,editing)}}},
                        enabled=canEdit&&selectedHere.isNotEmpty(),modifier=Modifier.fillMaxWidth()){
                        Icon(Icons.Rounded.RemoveCircleOutline,null,modifier=Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp));Text("Убрать из очереди (${selectedHere.size})")
                    }
                    Text("Музыкальные файлы и текущее воспроизведение сохраняются",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }else if(editingId!=null){
            Text(if(loading)"Проверка состава очереди…" else "Сохранённая очередь недоступна",Modifier.padding(17.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
        }else{
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("resume-music"),contentPadding=PaddingValues(bottom=12.dp)){
                if(!loading&&!error&&sessions.isEmpty())item{Text("Здесь появятся незавершённые сеансы воспроизведения.",Modifier.padding(17.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
                items(sessions,key={it.session.id}){resolved->
                    val saved=resolved.session
                    val track=resolved.currentId?.let(rows::get)
                    val title=saved.playlistId?.let(playlistNames::get)
                    val date=Instant.ofEpochMilli(saved.updatedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))
                    Surface(Modifier.fillMaxWidth().padding(horizontal=17.dp,vertical=6.dp),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceVariant){
                        Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                            if(!title.isNullOrBlank())Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                            Text(if(track!=null)displayAudioTitle(track) else if(loading)"Проверка доступности…" else "Трек недоступен",style=MaterialTheme.typography.bodyLarge)
                            Text("Трек ${saved.index+1} из ${saved.entries.size} · позиция ${position(saved.positionMs)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(saved.durationMs>0)LinearProgressIndicator(progress={(saved.positionMs.toDouble()/saved.durationMs).coerceIn(0.0,1.0).toFloat()},modifier=Modifier.fillMaxWidth(),drawStopIndicator={})
                            Text(date,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(resolved.missing>0&&!loading)Text("Недоступно: ${resolved.missing} из ${saved.entries.size}",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                            if(store!=null)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                TextButton({editingId=saved.id;selected=emptySet()},enabled=!busy){Text("Состав очереди")}
                                TextButton({deletingId=saved.id},enabled=canEdit){Text("Удалить сеанс")}
                            }
                            Button(onClick={if(resolved.missing>0)confirmation=resolved else onResume(resolved)},enabled=track!=null&&!loading&&!busy&&!error,modifier=Modifier.fillMaxWidth()){
                                Icon(Icons.Rounded.PlayArrow,null);Text("Продолжить")
                            }
                        }
                    }
                }
            }
        }
    }
    removeMissing?.let{expected->
        AlertDialog(onDismissRequest={removeMissing=null},title={Text("Убрать недоступные треки?")},
            text={Text("Из сохранённой очереди будут убраны ${expected.missing} вхождений. Музыкальные файлы не удаляются.")},
            confirmButton={TextButton({
                removeMissing=null
                if(canEdit&&editing==expected)store?.let{target->
                    val ids=expected.ids.indices.filter{expected.ids[it]==null}.map{expected.session.occurrenceIds[it]}.toSet()
                    mutate{target.edit(expected.session,ids,expected)}
                }
            },enabled=canEdit&&editing==expected){Text("Убрать из очереди")}},
            dismissButton={TextButton({removeMissing=null}){Text("Отмена")}})
    }
    if(clearAll || deletingId!=null)AlertDialog(onDismissRequest={clearAll=false;deletingId=null},
        title={Text(if(clearAll)"Очистить все сохранённые сеансы?" else "Удалить сохранённую очередь?")},
        text={Text("Файлы и текущее воспроизведение сохранятся.")},
        confirmButton={TextButton({val all=clearAll;val id=deletingId;clearAll=false;deletingId=null
            store?.let { target->mutate {if(all)target.clearSaved() else if(id!=null)target.deleteSaved(id)} }
            if(all || editingId==id)leaveQueue()
        },enabled=canEdit){Text("Удалить",color=MaterialTheme.colorScheme.error)}},
        dismissButton={TextButton({clearAll=false;deletingId=null}){Text("Отмена")}})
    confirmation?.let { chosen ->
        AlertDialog(onDismissRequest={confirmation=null},title={Text("Часть очереди недоступна")},
            text={Text("Пропустить ${chosen.missing} недоступных треков и продолжить с прежней позиции? Сохранённая очередь будет восстановлена только из доступных файлов.")},
            confirmButton={TextButton({confirmation=null;onResume(chosen)},enabled=!loading&&!error&&sessions.any{it==chosen}){Text("Продолжить с доступными")}},
            dismissButton={TextButton({confirmation=null}){Text("Отмена")}})
    }
}

package com.domenota.medialoader.ui.screens.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.domenota.medialoader.data.tasks.*
import com.domenota.medialoader.ui.components.ReportDrawerScreen
import com.domenota.medialoader.ui.components.BrainCircuitOutline
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

internal const val TASK_CENTER_ROUTE = "task-center"

@Composable
internal fun TaskCenterAction(onOpen: () -> Unit) {
    val counts=remember { TaskCenter.registry.tasks.map { rows -> rows.count { it.active } }.distinctUntilChanged() }
    val count by counts.collectAsStateWithLifecycle(initialValue=0)
    IconButton(onOpen) {
        BadgedBox(badge = { if (count > 0) Badge { Text(count.toString()) } }) {
            Icon(Icons.Rounded.Memory, "Центр задач", tint=MaterialTheme.colorScheme.primary)
        }
    }
}
private fun sectionIcon(section:TaskSection):ImageVector=when(section) {
    TaskSection.DOWNLOADS->Icons.Rounded.Download
    TaskSection.LIBRARY->Icons.Rounded.PhotoLibrary
    TaskSection.MUSIC->Icons.Rounded.MusicNote
    TaskSection.EDITORS->Icons.Rounded.Tune
    TaskSection.MODELS->BrainCircuitOutline
}
private fun kindIcon(kind:TaskKind):ImageVector=when(kind) {
    TaskKind.DOWNLOAD,TaskKind.BULK->Icons.Rounded.Download
    TaskKind.PROFILE,TaskKind.COMMUNITY,TaskKind.OCR->Icons.Rounded.Search
    TaskKind.EXPORT,TaskKind.FILE_OPERATION->Icons.Rounded.Folder
    TaskKind.BATCH_EDIT->Icons.Rounded.Tune
    TaskKind.WAVEFORM,TaskKind.LOUDNESS->Icons.Rounded.GraphicEq
    TaskKind.MODEL->BrainCircuitOutline
    TaskKind.VISUAL_DUPLICATES->Icons.Rounded.PhotoLibrary
    TaskKind.AUDIO_DUPLICATES->Icons.Rounded.LibraryMusic
}
@Composable private fun sectionColor(section:TaskSection):Color {
    val light=MaterialTheme.colorScheme.background.luminance()>.5f
    return when(section){
        TaskSection.DOWNLOADS->if(light)Color(0xFF235FA0) else Color(0xFF69A9EF)
        TaskSection.LIBRARY->if(light)Color(0xFF6441A8) else Color(0xFFA79AEE)
        TaskSection.MUSIC->if(light)Color(0xFF197660) else Color(0xFF61BAA4)
        TaskSection.EDITORS->if(light)Color(0xFF96551F) else Color(0xFFDC9D66)
        TaskSection.MODELS->if(light)Color(0xFF8E459F) else Color(0xFFC28DDC)
    }
}
@Composable private fun statusColor(status:TaskStatus):Color {
    val light=MaterialTheme.colorScheme.background.luminance()>.5f
    return when(status){
        TaskStatus.FAILED->if(light)Color(0xFFB53D50) else Color(0xFFEC7885)
        TaskStatus.PARTIAL,TaskStatus.WAITING,TaskStatus.QUEUED->if(light)Color(0xFF996500) else Color(0xFFFFBD19)
        TaskStatus.RUNNING,TaskStatus.COMPLETE->if(light)Color(0xFF25835E) else Color(0xFF57BE92)
        else->MaterialTheme.colorScheme.onSurfaceVariant
    }
}
@Composable private fun CategoryIcon(icon:ImageVector,color:Color,size:Int=36){
    Box(Modifier.size(size.dp).background(color.copy(alpha=.15f),RoundedCornerShape(11.dp)),contentAlignment=Alignment.Center){
        Icon(icon,null,Modifier.size((size*.58f).dp),tint=color)
    }
}
@Composable
internal fun TaskCenterScreen(onBack: () -> Unit) {
    ReportDrawerScreen(true)
    val tasks by TaskCenter.registry.tasks.collectAsStateWithLifecycle()
    val live by TaskCenter.registry.liveTasks.collectAsStateWithLifecycle()
    val known by TaskCenter.registry.registeredOwners.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    if(settingsOpen)TaskSettingsDialog{settingsOpen=false}
    TaskCenterContent(tasks,live,known,onBack,{settingsOpen=true},TaskCenter.registry::dispatch)
}

/** One real task projection; cards never start or infer executor actions. */
@Composable
internal fun TaskCenterContent(tasks:List<TaskSnapshot>,live:List<TaskSnapshot>,known:Set<TaskOwner>,
    onBack:()->Unit,onSettings:()->Unit,dispatch:(String,TaskCommand)->Boolean) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var errorsOnly by rememberSaveable { mutableStateOf(false) }
    var focus by rememberSaveable { mutableStateOf<String?>(null) }
    var openSections by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val snackbar=remember { SnackbarHostState() }
    val scope=rememberCoroutineScope()
    val command:(String,TaskCommand)->Unit={id,action->if(!dispatch(id,action))scope.launch{snackbar.showSnackbar("Действие недоступно")}}
    val active=remember(tasks){TaskCenterPresentation.active(tasks)}
    val waiting=remember(tasks){TaskCenterPresentation.waiting(tasks)}
    val paused=remember(tasks){TaskCenterPresentation.paused(tasks)}
    val history=remember(tasks){TaskCenterPresentation.history(tasks)}
    val errors=remember(tasks){TaskCenterPresentation.errors(tasks)}
    val liveKinds=remember(live){live.groupBy{it.kind}}
    val visible=remember(tab,filter,errorsOnly,active,waiting,paused,history,errors,focus){
        TaskCenterPresentation.focusFirst(when(tab){
            1->when(filter){1->active;2->waiting;3->paused;else->active+waiting+paused}
            2->if(errorsOnly)errors else history
            else->emptyList()
        },focus)
    }
    fun openTask(row:TaskSnapshot){if(row.idle)return;focus=row.id;filter=0;errorsOnly=false;tab=if(row.status.terminal)2 else 1}
    val activeCount=active.size+waiting.size+paused.size
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onBack){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")}
            Text("Центр задач",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall)
            IconButton(onSettings){Icon(Icons.Rounded.Settings,"Настройки")}
        }
        TabRow(selectedTabIndex=tab){
            listOf("Обзор",if(activeCount>0)"Задачи · $activeCount" else "Задачи","История").forEachIndexed{index,label->
                Tab(selected=tab==index,onClick={tab=index;focus=null;filter=0;errorsOnly=false},
                    selectedContentColor=MaterialTheme.colorScheme.primary,
                    unselectedContentColor=MaterialTheme.colorScheme.onSurfaceVariant,text={Text(label)})
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(12.dp),
            verticalArrangement=Arrangement.spacedBy(10.dp)){
            if(tab==0){
                item("summary"){
                    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp)){
                        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
                            Text("Состояние процессов",style=MaterialTheme.typography.titleMedium)
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)){
                                TaskCounter("Активные",active.size,statusColor(TaskStatus.RUNNING),Modifier.weight(1f)){focus=null;filter=1;tab=1}
                                TaskCounter("Ожидают",waiting.size,statusColor(TaskStatus.WAITING),Modifier.weight(1f)){focus=null;filter=2;tab=1}
                                TaskCounter("Ошибки истории",errors.size,statusColor(TaskStatus.FAILED),Modifier.weight(1f)){focus=null;errorsOnly=true;tab=2}
                            }
                            if(paused.isNotEmpty())TextButton({focus=null;filter=3;tab=1}){Text("Приостановлено: ${paused.size}")}
                        }
                    }
                }
                item("process-header"){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Text("Процессы приложения",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                    TextButton({openSections=if(openSections.size==TaskSection.entries.size)arrayListOf()else ArrayList(TaskSection.entries.map{it.name})}){Text(if(openSections.size==TaskSection.entries.size)"Свернуть" else "Развернуть")}
                }}
                items(TaskSection.entries,key={it.name}){section->
                    val sectionRows=section.kinds.flatMap{liveKinds[it].orEmpty()}
                    val running=TaskCenterPresentation.active(sectionRows).size
                    val pending=TaskCenterPresentation.waiting(sectionRows).size
                    val sleeping=TaskCenterPresentation.paused(sectionRows).size
                    val opened=section.name in openSections
                    val unknown=section.kinds.any{TaskCenterPresentation.owner(it) !in known && liveKinds[it].isNullOrEmpty()}
                    val summary=buildList {
                        if(running>0)add("$running в работе")
                        if(pending>0)add("$pending ожидает")
                        if(sleeping>0)add("$sleeping на паузе")
                    }.joinToString(" · ").ifBlank{if(unknown)"Нет данных о состоянии" else "Нет задач"}
                    val color=sectionColor(section)
                    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp)){
                        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
                            Row(Modifier.fillMaxWidth().clickable{
                                openSections=ArrayList(if(opened)openSections-section.name else openSections+section.name)
                            }.heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                                CategoryIcon(sectionIcon(section),color)
                                Column(Modifier.weight(1f)){
                                    Text(section.label,style=MaterialTheme.typography.titleMedium)
                                    Text(summary,style=MaterialTheme.typography.bodySmall,color=when{running>0->statusColor(TaskStatus.RUNNING);pending>0->statusColor(TaskStatus.WAITING);else->MaterialTheme.colorScheme.onSurfaceVariant})
                                }
                                Icon(if(opened)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,if(opened)"Свернуть" else "Развернуть",tint=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if(opened)section.kinds.forEach{kind->
                                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.55f))
                                val rows=liveKinds[kind].orEmpty()
                                if(rows.isEmpty()){
                                    Row(Modifier.fillMaxWidth().heightIn(min=40.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                        Icon(kindIcon(kind),null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                                        Column(Modifier.weight(1f)){
                                            Text(kind.label,style=MaterialTheme.typography.bodyMedium)
                                            Text(TaskCenterPresentation.idleLabel(kind,known),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                } else {
                                    rows.take(2).forEach { row->
                                        Row(Modifier.fillMaxWidth().then(if(!row.idle)Modifier.clickable{openTask(row)}else Modifier).padding(vertical=3.dp),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                            Icon(kindIcon(kind),null,Modifier.padding(top=2.dp).size(20.dp),tint=color)
                                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
                                                Text(TaskCenterPresentation.title(row),style=MaterialTheme.typography.bodyMedium)
                                                TaskRowContent(row,true,command)
                                            }
                                            if(!row.idle)Icon(Icons.Rounded.ChevronRight,"К задаче",tint=MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    if(rows.size>2)TextButton({focus=null;filter=0;tab=1}){Text("Все задачи (${rows.size})")}
                                }
                            }
                        }
                    }
                }
            }else{
                item("filters"){
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        if(tab==1)listOf("Все","Активные","Ожидают","На паузе").forEachIndexed{index,label->
                            FilterChip(selected=filter==index,onClick={filter=index;focus=null},label={Text(label)})
                        }else{
                            FilterChip(selected=!errorsOnly,onClick={errorsOnly=false;focus=null},label={Text("Все")})
                            FilterChip(selected=errorsOnly,onClick={errorsOnly=true;focus=null},label={Text("Ошибки")})
                        }
                    }
                }
                if(visible.isEmpty())item("empty"){
                    Text("Нет задач",Modifier.padding(16.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(visible,key={it.id}){row->TaskCard(row,focus==row.id,command)}
            }
        }
        SnackbarHost(snackbar)
    }
}
@Composable private fun TaskCounter(label:String,count:Int,color:Color,modifier:Modifier=Modifier,onClick:()->Unit){
    Column(modifier.clickable(onClick=onClick).padding(vertical=4.dp)){
        Text(count.toString(),style=MaterialTheme.typography.headlineSmall,color=if(count==0)MaterialTheme.colorScheme.onSurfaceVariant else color)
        Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun TaskCard(row:TaskSnapshot,focused:Boolean,command:(String,TaskCommand)->Unit){
    var expanded by rememberSaveable(row.id,focused){mutableStateOf(focused)}
    var limit by rememberSaveable(row.id){mutableIntStateOf(10)}
    val canExpand=TaskCenterPresentation.expandable(row)
    val section=TaskSection.entries.first{row.kind in it.kinds}
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp)){
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
            Row(Modifier.fillMaxWidth().then(if(canExpand)Modifier.clickable{expanded=!expanded}else Modifier),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                CategoryIcon(kindIcon(row.kind),sectionColor(section))
                Column(Modifier.weight(1f)){
                    Text(TaskCenterPresentation.title(row),style=MaterialTheme.typography.titleMedium)
                    if(row.status.terminal&&row.updatedAt>0)Text(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(row.updatedAt)),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(canExpand)Icon(if(expanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,if(expanded)"Свернуть" else "Развернуть")
            }
            TaskRowContent(row,false,command)
            if(expanded&&canExpand){
                row.stage?.let{Text(it.label,style=MaterialTheme.typography.bodySmall)}
                if(row.errors>0)Text("Ошибки: ${row.errors}",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                row.children.take(limit).forEachIndexed{index,child->
                    HorizontalDivider()
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        Text("${index+1}. ${TaskCenterPresentation.title(child)}",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                        Text(child.status.label,style=MaterialTheme.typography.labelSmall,color=statusColor(child.status))
                    }
                    TaskCenterPresentation.progressText(child)?.let{Text(it,style=MaterialTheme.typography.labelSmall)}
                    TaskCommands(child,command)
                }
                if(row.children.size>limit)TextButton({limit+=10}){Text("Ещё (${row.children.size-limit})")}
            }
        }
    }
}
@Composable private fun TaskRowContent(row:TaskSnapshot,compact:Boolean,command:(String,TaskCommand)->Unit){
    val progress=row.files?.fraction ?: row.progress?.fraction ?: row.percent?.let{it/100f}
    val color=statusColor(row.status)
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
        Column(Modifier.weight(1f)){
            Text(if(row.idle)"Не запущено" else if(row.status==TaskStatus.WAITING&&row.wait!=null)"Ожидание · ${row.wait.label}" else if(compact&&row.status==TaskStatus.COMPLETE)"Готово" else row.status.label,
                style=MaterialTheme.typography.bodySmall,color=color)
            TaskCenterPresentation.progressText(row)?.let{Text(it,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
        if(TaskCenterPresentation.showBar(row)&&progress!=null)Text("${(progress*100).toInt()}%",style=MaterialTheme.typography.labelMedium,color=color)
    }
    if(TaskCenterPresentation.showBar(row)){
        if(progress!=null)LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth().height(4.dp),color=color)
        else if(row.active)LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp),color=color)
    }
    if(compact&&row.errors>0)Text("Ошибки: ${row.errors}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
    TaskCommands(row,command)
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun TaskCommands(row:TaskSnapshot,command:(String,TaskCommand)->Unit){
    if(row.commands.isEmpty())return
    FlowRow(horizontalArrangement=Arrangement.spacedBy(3.dp)){
        row.commands.forEach{action->
            TextButton({command(row.id,action)}){
                Text(when{row.idle&&action==TaskCommand.RESUME->"Начать";action==TaskCommand.RETRY&&row.children.isEmpty()&&row.files==null->"Повторить загрузку";else->action.label})
            }
        }
    }
}

@Composable
private fun TaskSettingsDialog(onClose:()->Unit) {
    val value by TaskHistoryStore.settings.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("Очистить историю задач?")},
        text={Text("Файлы, результаты и история загрузок сохранятся.")},
        confirmButton={TextButton({TaskCenter.registry.clearHistory();confirm=false}){Text("Очистить")}},
        dismissButton={TextButton({confirm=false}){Text("Отмена")}})
    else AlertDialog(onDismissRequest=onClose,title={Text("Настройки Центра задач")},
        text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Хранение истории")
                TaskSettings.RETENTION.forEach { days ->
                    Row(Modifier.fillMaxWidth().clickable { TaskHistoryStore.update(value.copy(retention=days)) },verticalAlignment=Alignment.CenterVertically) {
                        RadioButton(selected=value.retention==days,onClick={TaskHistoryStore.update(value.copy(retention=days))})
                        Text(if(days==0) "Без ограничений" else "$days дн.")
                    }
                }
                Row(verticalAlignment=Alignment.CenterVertically) { Text("Тяжёлая индексация при зарядке",Modifier.weight(1f)); Switch(value.charging,{TaskHistoryStore.update(value.copy(charging=it))}) }
                Row(verticalAlignment=Alignment.CenterVertically) { Text("Учитывать энергосбережение",Modifier.weight(1f)); Switch(value.batterySaver,{TaskHistoryStore.update(value.copy(batterySaver=it))}) }
                Row(verticalAlignment=Alignment.CenterVertically) { Text("Уведомления о завершении",Modifier.weight(1f)); Switch(value.notifications,{TaskHistoryStore.update(value.copy(notifications=it))}) }
                TextButton({confirm=true}){Text("Очистить историю задач")}
            }
        },confirmButton={TextButton(onClose){Text("Готово")}})
}

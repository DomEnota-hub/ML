package com.domenota.medialoader.ui.screens.tasks

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.domenota.medialoader.data.tasks.*
import com.domenota.medialoader.ui.components.ReportDrawerScreen
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
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
    TaskSection.MODELS->Icons.Rounded.Memory
}
private fun kindIcon(kind:TaskKind):ImageVector=when(kind) {
    TaskKind.DOWNLOAD,TaskKind.BULK->Icons.Rounded.Download
    TaskKind.PROFILE,TaskKind.COMMUNITY,TaskKind.OCR->Icons.Rounded.Search
    TaskKind.EXPORT,TaskKind.FILE_OPERATION->Icons.Rounded.Folder
    TaskKind.BATCH_EDIT->Icons.Rounded.Tune
    TaskKind.WAVEFORM,TaskKind.LOUDNESS->Icons.Rounded.GraphicEq
    TaskKind.MODEL->Icons.Rounded.Memory
    TaskKind.VISUAL_DUPLICATES->Icons.Rounded.PhotoLibrary
    TaskKind.AUDIO_DUPLICATES->Icons.Rounded.LibraryMusic
}
@Composable private fun statusColor(status:TaskStatus)=when(status){
    TaskStatus.FAILED,TaskStatus.PARTIAL->MaterialTheme.colorScheme.error
    TaskStatus.RUNNING->MaterialTheme.colorScheme.primary
    else->MaterialTheme.colorScheme.onSurfaceVariant
}
@Composable
internal fun TaskCenterScreen(onBack: () -> Unit) {
    ReportDrawerScreen(true)
    val tasks by TaskCenter.registry.tasks.collectAsStateWithLifecycle()
    val live by TaskCenter.registry.liveTasks.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var errorsOnly by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var openSections by rememberSaveable { mutableStateOf(arrayListOf(TaskSection.LIBRARY.name,TaskSection.MUSIC.name)) }
    if(settingsOpen) TaskSettingsDialog { settingsOpen=false }
    val active=remember(tasks){TaskCenterPresentation.active(tasks)}
    val waiting=remember(tasks){TaskCenterPresentation.waiting(tasks)}
    val history=remember(tasks){TaskCenterPresentation.history(tasks)}
    val errors=remember(tasks){TaskCenterPresentation.errors(tasks)}
    val liveKinds=remember(live){live.groupBy{it.kind}}
    val visible=remember(tab,filter,errorsOnly,active,waiting,history,errors){
        when(tab){1->when(filter){1->active;2->waiting;else->active+waiting};2->if(errorsOnly)errors else history;else->emptyList()}
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onBack){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")}
            Text("Центр задач",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall)
            IconButton({settingsOpen=true}){Icon(Icons.Rounded.Settings,"Настройки")}
        }
        TabRow(selectedTabIndex=tab){
            listOf("Обзор","Задачи","История").forEachIndexed{index,label->
                Tab(selected=tab==index,onClick={tab=index;if(index==1)filter=0;if(index==2)errorsOnly=false},text={Text(label)})
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(12.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp)){
            if(tab==0){
                item("summary"){
                    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp)){
                        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                            Text("Фоновые процессы",style=MaterialTheme.typography.titleMedium)
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){
                                TaskCounter("Активные",active.size,Modifier.weight(1f)){filter=1;tab=1}
                                TaskCounter("Ожидают",waiting.size,Modifier.weight(1f)){filter=2;tab=1}
                                TaskCounter("С ошибками",errors.size,Modifier.weight(1f)){errorsOnly=true;tab=2}
                            }
                        }
                    }
                }
                item("process-header"){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Text("Процессы приложения",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                    TextButton({openSections=if(openSections.size==TaskSection.entries.size)arrayListOf()else ArrayList(TaskSection.entries.map{it.name})}){Text(if(openSections.size==TaskSection.entries.size)"Свернуть" else "Развернуть")}
                }}
                items(TaskSection.entries,key={it.name}){section->
                    val sectionRows=section.kinds.flatMap{liveKinds[it].orEmpty()}
                    val running=sectionRows.count{it.active}
                    val pending=sectionRows.count{!it.active&&!it.idle&&!it.status.terminal}
                    val opened=section.name in openSections
                    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp)){
                        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                            Row(Modifier.fillMaxWidth().clickable{
                                openSections=ArrayList(if(opened)openSections-section.name else openSections+section.name)
                            },verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                                Icon(sectionIcon(section),null,tint=MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)){
                                    Text(section.label,style=MaterialTheme.typography.titleMedium)
                                    Text(if(running+pending==0)"Нет активных задач" else "$running выполняется · $pending ожидает",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(if(opened)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,if(opened)"Свернуть" else "Развернуть")
                            }
                            if(opened) section.kinds.forEach{kind->
                                HorizontalDivider()
                                val rows=liveKinds[kind].orEmpty()
                                if(rows.isEmpty()){
                                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                        Icon(kindIcon(kind),null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
                                        Column(Modifier.weight(1f)){
                                            Text(kind.label,style=MaterialTheme.typography.titleSmall)
                                            Text("Нет зарегистрированных задач",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                } else {
                                    Text(kind.label,style=MaterialTheme.typography.titleSmall)
                                    // Summary is bounded; full task list stays virtualized in Tasks.
                                    rows.take(2).forEach { row ->
                                        if(row.kind==TaskKind.DOWNLOAD)Text(TaskCenterPresentation.title(row),style=MaterialTheme.typography.bodyMedium)
                                        TaskRowContent(row,compact=true)
                                    }
                                    if(rows.size>2)TextButton({filter=0;tab=1}){Text("Все задачи (${rows.size})")}
                                }
                            }
                        }
                    }
                }
            }else{
                item("filters"){
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)){
                        if(tab==1) listOf("Все","Активные","Ожидают").forEachIndexed{index,label->
                            FilterChip(selected=filter==index,onClick={filter=index},label={Text(label)})
                        } else {
                            FilterChip(selected=!errorsOnly,onClick={errorsOnly=false},label={Text("Все")})
                            FilterChip(selected=errorsOnly,onClick={errorsOnly=true},label={Text("Ошибки")})
                        }
                    }
                }
                if(visible.isEmpty())item("empty"){
                    Text("Нет задач",Modifier.padding(16.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(visible,key={it.id}){row->TaskCard(row)}
            }
        }
    }
}
@Composable private fun TaskCounter(label:String,count:Int,modifier:Modifier=Modifier,onClick:()->Unit){
    Column(modifier.clickable(onClick=onClick).padding(vertical=4.dp)){
        Text(count.toString(),style=MaterialTheme.typography.headlineSmall,color=MaterialTheme.colorScheme.primary)
        Text(label,style=MaterialTheme.typography.labelSmall)
    }
}
@Composable private fun TaskCard(row:TaskSnapshot){
    var expanded by rememberSaveable(row.id){mutableStateOf(false)}
    var limit by rememberSaveable(row.id){mutableIntStateOf(10)}
    val canExpand=TaskCenterPresentation.expandable(row)
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp)){
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Row(Modifier.fillMaxWidth().then(if(canExpand)Modifier.clickable{expanded=!expanded}else Modifier),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Icon(kindIcon(row.kind),null,tint=MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)){
                    Text(TaskCenterPresentation.title(row),style=MaterialTheme.typography.titleSmall)
                    if(row.status.terminal&&row.updatedAt>0)Text(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(row.updatedAt)),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(canExpand)Icon(if(expanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,if(expanded) "Свернуть" else "Развернуть")
            }
            TaskRowContent(row,compact=false)
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
                    TaskCommands(child)
                }
                if(row.children.size>limit)TextButton({limit+=10}){Text("Ещё (${row.children.size-limit})")}
            }
        }
    }
}
@Composable private fun TaskRowContent(row:TaskSnapshot,compact:Boolean){
    val progress=row.files?.fraction ?: row.progress?.fraction ?: row.percent?.let{it/100f}
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
        Column(Modifier.weight(1f)){
            Text(if(row.idle)"Не запущено" else if(row.status==TaskStatus.WAITING&&row.wait!=null)"Ожидание · ${row.wait.label}" else row.status.label,
                style=MaterialTheme.typography.bodySmall,color=statusColor(row.status))
            TaskCenterPresentation.progressText(row)?.let{Text(it,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
        if(TaskCenterPresentation.showBar(row)&&progress!=null)Text("${(progress*100).toInt()}%",style=MaterialTheme.typography.labelMedium)
    }
    if(TaskCenterPresentation.showBar(row)){
        if(progress!=null)LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth().height(4.dp))
        else if(row.active)LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp))
    }
    if(compact&&row.errors>0)Text("Ошибки: ${row.errors}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
    TaskCommands(row)
}
@Composable private fun TaskCommands(row:TaskSnapshot){
    // Keep individual actions touchable and never fabricate a command from task kind.
    if(row.commands.isEmpty())return
    Column {
        row.commands.forEach{action->
            TextButton({TaskCenter.registry.dispatch(row.id,action)}){
                Text(if(row.idle&&action==TaskCommand.RESUME)"Начать" else action.label)
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

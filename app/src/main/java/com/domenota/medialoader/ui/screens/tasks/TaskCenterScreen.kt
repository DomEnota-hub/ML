package com.domenota.medialoader.ui.screens.tasks

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import com.domenota.medialoader.ui.screens.gallery.GalleryOcrController
import com.domenota.medialoader.ui.screens.gallery.OcrTaskChargeSetting
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.domenota.medialoader.data.tasks.*
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.logging.TaskJournalDisplay
import com.domenota.medialoader.ui.screens.AppLogViewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.domenota.medialoader.ui.components.ReportDrawerScreen
import com.domenota.medialoader.ui.components.BrainCircuitOutline
import com.domenota.medialoader.ui.theme.SettingIconTone
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
        TaskSection.LIBRARY->SettingIconTone.DRAWER.swatch(dark=!light).foreground
        TaskSection.MUSIC->if(light)Color(0xFF197660) else Color(0xFF61BAA4)
        TaskSection.EDITORS->if(light)Color(0xFF96551F) else Color(0xFFDC9D66)
        TaskSection.MODELS->SettingIconTone.AI_MODELS.swatch(dark=!light).foreground
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
internal fun TaskCenterScreen(journalAllowed:Boolean=false,canReadJournal:()->Boolean={false},ocr:GalleryOcrController?=null,initialOcrFocus:Boolean=false,onBack: () -> Unit) {
    ReportDrawerScreen(true)
    val tasks by TaskCenter.registry.tasks.collectAsStateWithLifecycle()
    val live by TaskCenter.registry.liveTasks.collectAsStateWithLifecycle()
    val known by TaskCenter.registry.registeredOwners.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var related by remember { mutableStateOf<TaskCorrelation?>(null) }
    LaunchedEffect(journalAllowed){if(!journalAllowed)related=null}
    BackHandler(enabled=settingsOpen){settingsOpen=false}
    if(settingsOpen)TaskSettingsScreen{settingsOpen=false}
    else TaskCenterContent(tasks,live,known,onBack,{settingsOpen=true},TaskCenter.registry::dispatch,
        onJournal=if(journalAllowed){{token->if(canReadJournal())related=token}}else null,ocr=ocr,initialOcrFocus=initialOcrFocus)
    related?.takeIf{journalAllowed}?.let{token->RelatedTaskJournal(token,canReadJournal){related=null}}
}

/** One real task projection; cards never start or infer executor actions. */
@Composable
internal fun TaskCenterContent(tasks:List<TaskSnapshot>,live:List<TaskSnapshot>,known:Set<TaskOwner>,
    onBack:()->Unit,onSettings:()->Unit,dispatch:(String,TaskCommand)->Boolean,onJournal:((TaskCorrelation)->Unit)?=null,
    ocr:GalleryOcrController?=null,initialOcrFocus:Boolean=false) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var historyFilter by rememberSaveable { mutableIntStateOf(0) }
    var focus by rememberSaveable { mutableStateOf<String?>(null) }
    var openSections by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var closedSections by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val snackbar=remember { SnackbarHostState() }
    val scope=rememberCoroutineScope()
    val command:(String,TaskCommand)->Unit={id,action->if(!dispatch(id,action))scope.launch{snackbar.showSnackbar("Действие недоступно")}}
    val active=remember(tasks){TaskCenterPresentation.active(tasks)}
    val waiting=remember(tasks){TaskCenterPresentation.waiting(tasks)}
    val paused=remember(tasks){TaskCenterPresentation.paused(tasks)}
    val history=remember(tasks){TaskCenterPresentation.history(tasks)}
    val errors=remember(tasks){TaskCenterPresentation.errors(tasks)}
    val liveKinds=remember(live){live.groupBy{it.kind}}
    val visible=remember(tab,filter,historyFilter,active,waiting,paused,history,errors,focus){
        TaskCenterPresentation.focusFirst(when(tab){
            1->when(filter){1->active;2->waiting;3->paused;else->active+waiting+paused}
            2->when(historyFilter){1->errors;2->TaskCenterPresentation.partial(tasks);else->history}
            else->emptyList()
        },focus)
    }
    fun openTask(row:TaskSnapshot){if(row.idle)return;focus=row.id;filter=0;historyFilter=0;tab=if(row.status.terminal)2 else 1}
    val activeCount=active.size+waiting.size+paused.size
    val overviewTasks=remember(active,waiting,paused,live,initialOcrFocus){
        val rows=active+waiting+paused
        val ocrRow=live.firstOrNull{it.kind==TaskKind.OCR}
        if(initialOcrFocus&&ocrRow!=null)listOf(ocrRow)+rows.filter{it.id!=ocrRow.id} else rows
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onBack){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")}
            Text("Центр задач",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall)
            IconButton(onSettings){Icon(Icons.Rounded.Settings,"Настройки")}
        }
        TabRow(selectedTabIndex=tab){
            listOf("Обзор",if(activeCount>0)"Задачи: $activeCount" else "Задачи","История").forEachIndexed{index,label->
                Tab(selected=tab==index,onClick={tab=index;focus=null;filter=0;historyFilter=0},
                    selectedContentColor=MaterialTheme.colorScheme.primary,
                    unselectedContentColor=MaterialTheme.colorScheme.onSurfaceVariant,text={Text(label)})
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(12.dp),
            verticalArrangement=Arrangement.spacedBy(10.dp)){
            if(tab==0){
                item("summary"){
                    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainer){
                        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                            Text("Состояние процессов",style=MaterialTheme.typography.titleSmall)
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)){
                                TaskCounter("Активные",active.size,statusColor(TaskStatus.RUNNING),Modifier.weight(1f)){focus=null;filter=1;tab=1}
                                TaskCounter("Ожидают",waiting.size,statusColor(TaskStatus.WAITING),Modifier.weight(1f)){focus=null;filter=2;tab=1}
                                TaskCounter("На паузе",paused.size,statusColor(TaskStatus.PAUSED),Modifier.weight(1f)){focus=null;filter=3;tab=1}
                                TaskCounter("Ошибки",errors.size,statusColor(TaskStatus.FAILED),Modifier.weight(1f)){focus=null;historyFilter=1;tab=2}
                            }
                        }
                    }
                }
                if(overviewTasks.isNotEmpty()) {
                    item("active-title"){Text(if(initialOcrFocus)"Задачи и распознавание текста" else "Сейчас в работе",style=MaterialTheme.typography.titleMedium)}
                    items(overviewTasks,key={"overview-"+it.id}){row->
                        TaskCard(row,initialOcrFocus&&row.kind==TaskKind.OCR,command,onJournal,ocr=ocr)
                    }
                }
                if(initialOcrFocus && overviewTasks.none{it.kind==TaskKind.OCR} && ocr!=null) item("ocr-focus-setting"){
                    OcrTaskChargeSetting(ocr,Modifier.padding(horizontal=8.dp))
                }
                item("process-header"){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Text("Процессы приложения",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                    TextButton({val all=openSections.size==TaskSection.entries.size;openSections=if(all)arrayListOf()else ArrayList(TaskSection.entries.map{it.name});closedSections=if(all)ArrayList(TaskSection.entries.map{it.name})else arrayListOf()}){Text(if(openSections.size==TaskSection.entries.size)"Свернуть" else "Развернуть")}
                }}
                items(TaskSection.entries,key={it.name}){section->
                    val sectionRows=section.kinds.flatMap{liveKinds[it].orEmpty()}
                    val running=TaskCenterPresentation.active(sectionRows).size
                    val pending=TaskCenterPresentation.waiting(sectionRows).size
                    val sleeping=TaskCenterPresentation.paused(sectionRows).size
                    val opened=section.name in openSections || (section.name !in closedSections && (running+pending+sleeping>0 || initialOcrFocus&&section==TaskSection.LIBRARY))
                    val unknown=section.kinds.any{TaskCenterPresentation.owner(it) !in known && liveKinds[it].isNullOrEmpty()}
                    val summary=buildList {
                        if(running>0)add("$running в работе")
                        if(pending>0)add("$pending ожидает")
                        if(sleeping>0)add("$sleeping на паузе")
                    }.joinToString(", ").ifBlank{if(unknown)"Состояние неизвестно" else "Нет задач"}
                    val color=sectionColor(section)
                    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(10.dp),color=MaterialTheme.colorScheme.surfaceContainerLow){
                        Column(Modifier.padding(horizontal=10.dp,vertical=4.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){
                            Row(Modifier.fillMaxWidth().clickable{
                                openSections=ArrayList(if(opened)openSections-section.name else openSections+section.name)
                                closedSections=ArrayList(if(opened)closedSections+section.name else closedSections-section.name)
                            }.heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                                CategoryIcon(sectionIcon(section),color)
                                Column(Modifier.weight(1f)){
                                    Text(section.label,style=MaterialTheme.typography.titleMedium)
                                    Text(summary,style=MaterialTheme.typography.labelSmall,color=when{running>0->statusColor(TaskStatus.RUNNING);pending>0->statusColor(TaskStatus.WAITING);else->MaterialTheme.colorScheme.onSurfaceVariant})
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
                                            if(TaskCenterPresentation.owner(kind) !in known)Text("Неизвестно",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                            else Text("—",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    if(rows.size>2)TextButton({focus=null;filter=0;tab=1}){Text("Все задачи ("+rows.size+")")}
                                }
                                if(kind==TaskKind.OCR&&ocr!=null&&overviewTasks.none{it.kind==TaskKind.OCR})OcrTaskChargeSetting(ocr)
                                if(kind==TaskKind.WAVEFORM)TaskCenterPresentation.latestConfirmed(tasks,kind)?.let{last->
                                    val result=last.details?.outcomes
                                    if(result!=null)Column(Modifier.fillMaxWidth().clickable{openTask(last)}.padding(start=30.dp,bottom=4.dp)){
                                        Text("Последний сохранённый результат · "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(last.updatedAt)),style=MaterialTheme.typography.labelSmall)
                                        Text("В кэше: "+result.cached+" · Создано: "+result.generated+" · Пропущено: "+result.skipped+" · Ошибки: "+result.failed,
                                            style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
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
                            FilterChip(selected=historyFilter==0,onClick={historyFilter=0;focus=null},label={Text("Все")})
                            FilterChip(selected=historyFilter==1,onClick={historyFilter=1;focus=null},label={Text("Ошибки")})
                            FilterChip(selected=historyFilter==2,onClick={historyFilter=2;focus=null},label={Text("Частичные")})
                        }
                    }
                }
                if(visible.isEmpty())item("empty"){
                    Text("Нет задач",Modifier.padding(16.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(visible,key={it.id}){row->TaskCard(row,focus==row.id,command,onJournal,ocr=ocr)}
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
@Composable private fun TaskCard(row:TaskSnapshot,focused:Boolean,command:(String,TaskCommand)->Unit,onJournal:((TaskCorrelation)->Unit)?,ocr:GalleryOcrController?=null){
    var expanded by rememberSaveable(row.id,focused){mutableStateOf(focused)}
    var limit by rememberSaveable(row.id){mutableIntStateOf(10)}
    val canExpand=TaskCenterPresentation.expandable(row)
    val section=TaskSection.entries.first{row.kind in it.kinds}
    val timeline=row.status.terminal
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(if(timeline)8.dp else 16.dp),color=if(timeline)MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer){
        Column(Modifier.padding(if(timeline)8.dp else 12.dp),verticalArrangement=Arrangement.spacedBy(if(timeline)3.dp else 6.dp)){
            Row(Modifier.fillMaxWidth().then(if(canExpand)Modifier.clickable{expanded=!expanded}else Modifier),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                CategoryIcon(kindIcon(row.kind),sectionColor(section))
                Column(Modifier.weight(1f)){
                    Text(TaskCenterPresentation.title(row),style=MaterialTheme.typography.titleMedium)
                    if(row.status.terminal&&row.updatedAt>0)Text(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(row.updatedAt)),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(canExpand)Icon(if(expanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,if(expanded)"Свернуть" else "Развернуть")
            }
            TaskRowContent(row,timeline,command)
            if(row.kind==TaskKind.OCR&&ocr!=null&&!timeline){HorizontalDivider();OcrTaskChargeSetting(ocr)}
            if(expanded&&canExpand){
                TaskResultDetails(row,onJournal)
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
            Text(if(row.idle)"Не запущено" else if(row.status==TaskStatus.WAITING&&row.wait!=null)"Ожидание: ${row.wait.label}" else if(compact&&row.status==TaskStatus.COMPLETE)"Готово" else row.status.label,
                style=MaterialTheme.typography.bodySmall,color=color)
            TaskCenterPresentation.progressText(row)?.let{Text(it,style=if(compact)MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurface)}
        }
        if(TaskCenterPresentation.showBar(row)&&progress!=null)Text("${(progress*100).toInt()}%",style=if(compact)MaterialTheme.typography.labelMedium else MaterialTheme.typography.headlineSmall,color=color)
    }
    if(TaskCenterPresentation.showBar(row)){
        if(progress!=null)LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth().height(5.dp),color=color,trackColor=MaterialTheme.colorScheme.outlineVariant)
        else if(row.active)LinearProgressIndicator(Modifier.fillMaxWidth().height(5.dp),color=color,trackColor=MaterialTheme.colorScheme.outlineVariant)
    }
    if(row.errors>0)Text("Ошибки: ${row.errors}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
    if(row.status.terminal)row.details?.outcomes?.let{v->Text("Создано: "+v.generated+" · В кэше: "+v.cached+" · Пропущено: "+v.skipped,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    TaskCommands(row,command)
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun TaskCommands(row:TaskSnapshot,command:(String,TaskCommand)->Unit){
    if(row.commands.isEmpty())return
    FlowRow(horizontalArrangement=Arrangement.spacedBy(3.dp)){
        row.commands.forEach{action->
            TextButton({command(row.id,action)}){
                if(action==TaskCommand.PAUSE||action==TaskCommand.RESUME){Icon(if(action==TaskCommand.PAUSE)Icons.Rounded.Pause else Icons.Rounded.PlayArrow,null,Modifier.size(18.dp));Spacer(Modifier.width(4.dp))}
                Text(when{row.idle&&action==TaskCommand.RESUME->"Начать";action==TaskCommand.RETRY&&row.kind==TaskKind.DOWNLOAD&&row.children.isEmpty()&&row.files==null->"Повторить загрузку";else->action.label})
            }
        }
    }
}

@Composable private fun TaskResultDetails(row:TaskSnapshot,onJournal:((TaskCorrelation)->Unit)?){
    val details=row.details
    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
    if(details==null&&row.status.terminal)Text("Подробности не сохранены",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    details?.startedAt?.let{start->
        DetailLine("Начало",DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(start)))
        if(row.status.terminal&&row.updatedAt>=start)DetailLine("Продолжительность",TaskCenterPresentation.duration(row.updatedAt-start))
    }
    if(row.status.terminal&&row.updatedAt>0)DetailLine("Завершение",DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(row.updatedAt)))
    details?.trigger?.let{DetailLine("Запуск",TaskCenterPresentation.triggerLabel(it))}
    row.source?.let{DetailLine("Источник",it.label)}
    row.stage?.let{DetailLine("Этап",it.label)}
    row.wait?.let{DetailLine("Условие ожидания",it.label)}
    if(details?.recovered==true)Text("Продолжено с контрольной точки",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    details?.outcomes?.let{outcomes->
        Text("Результат обработки",style=MaterialTheme.typography.titleSmall)
        DetailLine("Проверено",outcomes.checked.toString())
        DetailLine("Создано",outcomes.generated.toString())
        DetailLine("Уже в кэше",outcomes.cached.toString())
        DetailLine("Пропущено",outcomes.skipped.toString())
        if(row.kind==TaskKind.WAVEFORM&&outcomes.skipped>0)Text("Пропуски не равны ошибкам: для части треков длительность могла быть недоступна",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        DetailLine("Ошибки",outcomes.failed.toString())
    }
    if(row.errors>0||row.status==TaskStatus.FAILED){
        val failure=details?.failure
        Text(failure?.label?:"Причина не сохранена",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)
        if(details?.outcomes==null&&row.errors>0)DetailLine("Ошибки",row.errors.toString())
        failure?.let{TaskCenterPresentation.advice(it)}?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
    details?.checkpoints?.let{summary->
        var expanded by rememberSaveable(row.id){mutableStateOf(false)}
        Row(Modifier.fillMaxWidth().clickable{expanded=!expanded}.heightIn(min=44.dp),verticalAlignment=Alignment.CenterVertically){
            Text("Пакеты (${summary.count})",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
            Icon(if(expanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,if(expanded)"Свернуть пакеты" else "Раскрыть пакеты")
        }
        if(expanded){
            if(summary.count>summary.recent.size)Text("Последние ${summary.recent.size} из ${summary.count}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            summary.recent.forEach{batch->
                HorizontalDivider()
                Text("Пакет ${batch.ordinal}: проверено ${batch.outcomes.checked}",style=MaterialTheme.typography.bodySmall)
                Text("Создано: ${batch.outcomes.generated}; в кэше: ${batch.outcomes.cached}; пропущено: ${batch.outcomes.skipped}; ошибок: ${batch.outcomes.failed}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if(onJournal!=null)details?.correlation?.let{token->TextButton({onJournal(token)}){Text("Связанные события журнала")}}
}
@Composable private fun DetailLine(label:String,value:String){
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.Top){
        Text("$label:",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun RelatedTaskJournal(token:TaskCorrelation,allowed:()->Boolean,onClose:()->Unit){
    var text by remember(token){mutableStateOf<String?>(null)}
    var readable by rememberSaveable{mutableStateOf(true)}
    val latestAllowed by rememberUpdatedState(allowed)
    LaunchedEffect(token){
        if(!latestAllowed()){onClose();return@LaunchedEffect}
        val related=withContext(Dispatchers.IO){TaskJournalDisplay.related(AppLog.readText(),token)}
        if(!latestAllowed()){onClose();return@LaunchedEffect}
        text=related
    }
    AlertDialog(onDismissRequest=onClose,title={Text("События задачи")},text={
        when{
            text==null->Text("Подготовка журнала…")
            text.isNullOrBlank()->Text("Связанные события не сохранились в журнале")
            else->AppLogViewer(text.orEmpty(),readable,{readable=it},Modifier.fillMaxWidth().height(420.dp))
        }
    },confirmButton={TextButton(onClose){Text("Закрыть")}})
}
@Composable private fun TaskSettingsScreen(onClose:()->Unit){
    val value by TaskHistoryStore.settings.collectAsStateWithLifecycle()
    var confirm by remember{mutableStateOf(false)}
    var retentionMenu by remember{mutableStateOf(false)}
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Очистить историю задач?")},
        text={Text("Файлы, результаты и история загрузок сохранятся.")},
        confirmButton={TextButton({TaskCenter.registry.clearHistory();confirm=false}){Text("Очистить",color=MaterialTheme.colorScheme.error)}},
        dismissButton={TextButton({confirm=false}){Text("Отмена")}})
    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClose){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")}
            Text("Настройки Центра задач",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall)
        }
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            item{
                Text("Фоновые процессы",style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Card(Modifier.fillMaxWidth().padding(top=6.dp),shape=RoundedCornerShape(16.dp)){
                    Column(Modifier.padding(12.dp)){
                        TaskSettingSwitch("Фоновая индексация только при зарядке","OCR и звуковые волны",value.charging){TaskHistoryStore.update(value.copy(charging=it))}
                        HorizontalDivider()
                        TaskSettingSwitch("Учитывать энергосбережение","Ожидать подходящих условий",value.batterySaver){TaskHistoryStore.update(value.copy(batterySaver=it))}
                    }
                }
            }
            item{
                Text("Уведомления",style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Card(Modifier.fillMaxWidth().padding(top=6.dp),shape=RoundedCornerShape(16.dp)){
                    Column(Modifier.padding(12.dp)){
                        TaskSettingSwitch("О завершении задач","Для поддерживаемых фоновых операций",value.notifications){TaskHistoryStore.update(value.copy(notifications=it))}
                    }
                }
            }
            item{
                Text("История",style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Card(Modifier.fillMaxWidth().padding(top=6.dp),shape=RoundedCornerShape(16.dp)){
                    Column(Modifier.padding(12.dp)){
                        Box{
                            Row(Modifier.fillMaxWidth().clickable{retentionMenu=true}.heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){
                                Column(Modifier.weight(1f)){
                                    Text("Хранение истории",style=MaterialTheme.typography.bodyLarge)
                                    Text(if(value.retention==0)"Без ограничений" else "${value.retention} дн.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Rounded.ExpandMore,"Выбрать срок")
                            }
                            DropdownMenu(expanded=retentionMenu,onDismissRequest={retentionMenu=false}){
                                TaskSettings.RETENTION.forEach{days->DropdownMenuItem(text={Text(if(days==0)"Без ограничений" else "$days дн.")},
                                    onClick={retentionMenu=false;TaskHistoryStore.update(value.copy(retention=days))})}
                            }
                        }
                        HorizontalDivider()
                        TextButton({confirm=true}){Text("Очистить историю задач",color=MaterialTheme.colorScheme.error)}
                    }
                }
            }
        }
    }
}
@Composable private fun TaskSettingSwitch(title:String,description:String,checked:Boolean,onChange:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
        Column(Modifier.weight(1f)){
            Text(title,style=MaterialTheme.typography.bodyLarge)
            Text(description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked,onChange,colors=SwitchDefaults.colors(uncheckedThumbColor=MaterialTheme.colorScheme.onSurfaceVariant,
            uncheckedTrackColor=MaterialTheme.colorScheme.surfaceVariant,uncheckedBorderColor=MaterialTheme.colorScheme.outline))
    }
}

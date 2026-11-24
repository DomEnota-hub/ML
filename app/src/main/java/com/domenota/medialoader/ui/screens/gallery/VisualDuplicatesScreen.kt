package com.domenota.medialoader.ui.screens.gallery

import com.domenota.medialoader.ui.model.displayFileName
import com.domenota.medialoader.ui.components.GalleryScanProgress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import com.domenota.medialoader.ui.screens.ZoomablePhoto
import com.domenota.medialoader.ui.components.DuplicateActionTrace
import com.domenota.medialoader.ui.components.DuplicateScanPanel
import com.domenota.medialoader.ui.components.DuplicateResultFilterPolicy
import com.domenota.medialoader.ui.components.DuplicateJournalDialog
import com.domenota.medialoader.ui.components.DuplicateJournalRow
import android.net.Uri
import android.widget.VideoView
import android.widget.MediaController
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.*
import com.domenota.medialoader.ui.components.DownloadThumbnail
import com.domenota.medialoader.ui.components.DuplicateTrashHost
import com.domenota.medialoader.ui.model.MediaKind
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun visualResultGroupCount(count:Int):String {
    val word=when {
        count%100 in 11..14 -> "групп"
        count%10==1 -> "группа"
        count%10 in 2..4 -> "группы"
        else -> "групп"
    }
    val predicate=if(count%100 !in 11..14 && count%10==1)"Найдена" else "Найдено"
    return "$predicate $count $word"
}

private data class VisualReplacementRequest(val keys:Set<String>?,val source:LibrarySource,val epoch:Long,val token:Long)

@Composable
internal fun VisualDuplicatesScreen(catalog:VisualDuplicateCatalog,ready:Boolean,phonePermission:Boolean,currentGeneration:()->Boolean,onBack:()->Unit,onAppTrash:suspend (String,String)->Unit,onRefresh:()->Unit,reportReady:Boolean=ready) {
    val context=LocalContext.current
    val refs=catalog.all
    val status by VisualDuplicateBackgroundScan.state.collectAsStateWithLifecycle()
    val privacyEpoch by DuplicatePrivacyGuard.changes.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var session by remember { mutableStateOf<VisualDuplicateSession?>(null) }
    // Only retain a result that has already been reconciled against a complete catalog.
    // It may be presented read-only while an unrelated MediaStore refresh is in flight.
    var lastVerified by remember { mutableStateOf<VisualDuplicateSession?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var restored by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var trash by remember { mutableStateOf<VisualDuplicateSession?>(null) }
    var journal by remember{mutableStateOf(false)}
    var showPartial by remember{mutableStateOf(false)}
    var history by remember{mutableStateOf(emptyList<VisualDuplicateSession>())}
    var storedPartial by remember{mutableStateOf<VisualDuplicateSession?>(null)}
    var journalLoaded by remember{mutableStateOf(false)}
    var replacement by remember{mutableStateOf<VisualReplacementRequest?>(null)}
    var newSearchAfterPause by remember{mutableStateOf(false)}
    var clearJournalConfirm by remember{mutableStateOf(false)}
    var clearPartialToo by remember{mutableStateOf(false)}
    var clearTargetToken by remember{mutableStateOf<Long?>(null)}
    var filter by remember{mutableStateOf<VisualMatch?>(null)}
    var preview by remember{mutableStateOf<Pair<VisualDuplicateGroup,String>?>(null)}
    var choosingScope by remember { mutableStateOf(false) }
    var openedHistoryAt by remember { mutableStateOf<Long?>(null) }
    var detailsExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(journal,status.running,status.stopped,status.token,privacyEpoch){
        journalLoaded=false
        history=VisualDuplicateSessionStore.history(context)
        storedPartial=VisualDuplicateSessionStore.partial(context)
        journalLoaded=true
    }
    LaunchedEffect(privacyEpoch){preview=null;journal=false;showPartial=false;replacement=null;newSearchAfterPause=false;clearJournalConfirm=false;clearTargetToken=null}
    val list=rememberLazyListState()
    val projectionGuard=emptySet<String>().takeIf{reportReady && phonePermission}
    val indexProjection=com.domenota.medialoader.ui.components.backgroundLibraryProjection(
        emptyMap<String,LibraryMediaRef>(),projectionGuard,refs,accessGuard=privacyEpoch) {
        com.domenota.medialoader.core.logging.DetailedPerformance.measure(com.domenota.medialoader.core.logging.PerfWork.DUPLICATE_INDEX){
            refs.groupBy{it.key}.mapNotNull{(id,rows)->rows.singleOrNull()?.let{id to it}}.toMap()
        }
    }
    val byId=if(indexProjection.ready)indexProjection.value else emptyMap()
    // Never authorize/report against a retained index for a superseded catalog.
    val currentProjection=com.domenota.medialoader.ui.components.backgroundLibraryProjection(
        null as VisualDuplicateSession?,projectionGuard,session?.result,byId,accessGuard=privacyEpoch,enabled=indexProjection.ready){
        session?.takeIf{it.result.privacyEpoch==privacyEpoch}?.reconcileById(byId)
    }
    val reconciled=currentProjection.value.takeIf{currentProjection.ready}
    val current=remember(session,reconciled){if(session!=null&&reconciled!=null)session?.copy(result=reconciled.result)?.normalized() else null}
    val displayReady=reportReady&&phonePermission&&indexProjection.ready&&currentProjection.ready
    val latestCompletedAt=status.session?.scannedAt
    val verified=displayReady&&current!=null&&
        (latestCompletedAt==null||current.scannedAt==latestCompletedAt||current.scannedAt==openedHistoryAt)
    val actionsReady=verified&&ready&&currentGeneration()
    // A background index rebuild must not erase the already verified screen. Never
    // borrow a previous report after another scan completes or privacy changes.
    val shown=if(verified)current else lastVerified?.takeIf{
        phonePermission&&loaded&&it.result.privacyEpoch==privacyEpoch&&
            (latestCompletedAt==null||it.scannedAt==latestCompletedAt||it.scannedAt==openedHistoryAt)
    }
    val stopped=VisualDuplicateBackgroundScan.stopped(context)
    val interrupted=!status.running&&VisualDuplicateBackgroundScan.interrupted(context)
    val resumeSource=VisualDuplicateBackgroundScan.resumeSource(context) ?: storedPartial?.result?.coverage?.source
    val sameScope=resumeSource==catalog.source
    val latestSession by rememberUpdatedState(session)
    val latestCatalog by rememberUpdatedState(catalog)
    val latestReady by rememberUpdatedState(ready && phonePermission)
    val latestPermission by rememberUpdatedState(phonePermission)
    val startBlocker = VisualScanStartGate.blocker(ready,phonePermission,loaded,saving,trash!=null,
        catalog.scan.isNotEmpty(),currentGeneration(),status.running)
    val latestEpoch by rememberUpdatedState(privacyEpoch)
    val liveGeneration by rememberUpdatedState(currentGeneration)
    fun startScan(keys: Set<String>? = null,resume:Boolean=false){
        val currentCatalog=latestCatalog
        val permission=latestPermission && (currentCatalog.source!=LibrarySource.PHONE ||
            com.domenota.medialoader.ui.hasPhoneLibraryAccess(context,AppMode.GALLERY))
        val blocker=VisualScanStartGate.blocker(latestReady,permission,loaded,saving,trash!=null,
            currentCatalog.scan.isNotEmpty(),liveGeneration() && DuplicatePrivacyGuard.generation(context)==latestEpoch,
            VisualDuplicateBackgroundScan.state.value.running)
        if(blocker!=null){error=blocker.message;return}
        if(VisualDuplicateBackgroundScan.interrupted(context)){
            val previousSource=VisualDuplicateBackgroundScan.resumeSource(context) ?: storedPartial?.result?.coverage?.source
            if(!resume||previousSource!=currentCatalog.source||keys!=VisualDuplicateBackgroundScan.resumeScope(context)){
                error="Выберите новый поиск и подтвердите замену незавершённой проверки";return
            }
        }else if(resume){error="Незавершённый поиск больше недоступен";return}
        val scanned=DuplicateFolderScope.filter(currentCatalog.scan,keys){it.item}
        if(scanned.isEmpty()) { error="В выбранных папках нет доступных файлов";return }
        openedHistoryAt=null
        error=null
        if(!VisualDuplicateBackgroundScan.start(context,scanned,currentCatalog.source,latestEpoch,keys))
            error="Не удалось запустить поиск. Проверьте состояние журнала и повторите."
    }
    fun requestNewScan(keys:Set<String>?){
        if(DuplicateFolderScope.filter(latestCatalog.scan,keys){it.item}.isEmpty()){
            error="В выбранных папках нет доступных файлов";return
        }
        if(VisualDuplicateBackgroundScan.interrupted(context)){
            replacement=VisualReplacementRequest(keys?.toSet(),latestCatalog.source,latestEpoch,VisualDuplicateBackgroundScan.state.value.token)
        }else startScan(keys)
    }
    fun chooseNewSearch(){
        if(status.running){
            if(status.committing||newSearchAfterPause)return
            newSearchAfterPause=VisualDuplicateBackgroundScan.pause(context)
            if(!newSearchAfterPause)error="Не удалось приостановить поиск"
        }else if(startBlocker==null)choosingScope=true
    }
    LaunchedEffect(status.running,newSearchAfterPause,privacyEpoch){
        if(newSearchAfterPause&&!status.running){
            newSearchAfterPause=false
            choosingScope=true
        }
    }
    val partial=(if(status.running)status.partial else if(interrupted)status.partial?:storedPartial?.result else null)
        ?.takeIf{it.privacyEpoch==privacyEpoch}
    val partialProjection=com.domenota.medialoader.ui.components.backgroundLibraryProjection(
        emptyList<VisualDuplicateGroup>(),projectionGuard,partial,byId,accessGuard=privacyEpoch,
        enabled=indexProjection.ready&&partial!=null){
        partial?.groups.orEmpty().mapNotNull{group->
            val files=group.files.filter{f->DuplicatePrivacyGuard.accepts(context,privacyEpoch,f.id)&&
                byId[f.id]?.let{VisualDuplicateScanner.matchesCatalog(f,it)}==true}
            if(files.size>=2)group.copy(files=files) else null
        }
    }
    val partialReady=reportReady&&phonePermission&&indexProjection.ready&&partialProjection.ready&&partial!=null
    val partialGroups=if(partialReady)partialProjection.value else emptyList()
    val partialCanShow=partialReady&&partialGroups.isNotEmpty()&&!saving&&trash==null
    val partialCount=if(partialReady)partialGroups.size else null
    val partialAbsent=partial==null&&journalLoaded&&!status.running
    fun openPartial(){if(partialCanShow){preview=null;showPartial=true}}
    fun exactBytes(value:List<VisualDuplicateGroup>)=value.filter{it.reason==VisualMatch.EXACT}.sumOf{g->g.files.sumOf{it.bytes}-(g.files.firstOrNull()?.bytes?:0L)}
    LaunchedEffect(privacyEpoch) {
        loaded=false
        lastVerified=null
        session=VisualDuplicateSessionStore.load(context)
        loaded=true
    }
    LaunchedEffect(verified,current,privacyEpoch) {
        if(verified&&current?.result?.privacyEpoch==privacyEpoch)lastVerified=current
    }
    LaunchedEffect(status.session,loaded,trash) {
        if(loaded&&trash==null) status.session?.let { fresh ->
            if(!VisualDuplicateSessionStore.isDeleted(context,fresh.scannedAt) &&
                fresh.scannedAt!=session?.scannedAt){session=fresh;restored=false}
        }
    }
    LaunchedEffect(current,actionsReady,loaded,status.running,trash) {
        if(actionsReady&&loaded&&!status.running&&trash==null&&current!=null&&current!=session)session=current
    }
    LaunchedEffect(current?.scannedAt,loaded,ready,restored) {
        if(loaded&&ready&&!restored&&current!=null){
            list.scrollToItem(current.index,current.offset)
            restored=true
        }
    }
    LaunchedEffect(list,restored,status.running,saving,current?.scannedAt,ready,trash) {
        if(restored&&ready&&!status.running&&!saving&&trash==null&&current!=null) snapshotFlow{Triple(list.firstVisibleItemIndex,list.firstVisibleItemScrollOffset,list.isScrollInProgress)}
            .distinctUntilChanged().collect{(index,offset,scrolling)->
                if(!scrolling){
                    val previous=latestSession ?: return@collect
                    if(previous.index!=index||previous.offset!=offset){
                        val next=previous.copy(index=index,offset=offset).normalized()
                        session=next
                        if(!VisualDuplicateSessionStore.savePosition(context,next.scannedAt,next.index,next.offset))error="Не удалось сохранить позицию"
                    }
                }
            }
    }
    fun persist(next:VisualDuplicateSession){
        if(VisualDuplicateSessionStore.isDeleted(context,next.scannedAt))return
        session=next.normalized()
        saving=true
        scope.launch {
            try{if(!VisualDuplicateSessionStore.save(context,next))error="Не удалось сохранить выбор"}
            finally{saving=false}
        }
    }
    if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("Переместить выбранные файлы в корзину?")},
        text={Text("Похожие файлы могут различаться. Сохраняемый экземпляр будет повторно проверен. Окончательное удаление не выполняется.")},
        confirmButton={TextButton({confirm=false;if(actionsReady&&latestReady&&liveGeneration()&&DuplicatePrivacyGuard.generation(context)==latestEpoch)trash=current?.takeIf{it.selected.isNotEmpty()}}){Text("Переместить")}},
        dismissButton={TextButton({confirm=false}){Text("Отмена")}})
    trash?.let { snapshot ->
        val request=remember(snapshot){
            val groups=snapshot.result.groups.filter{g->g.files.any{it.id in snapshot.selected}}.map{g->
                DuplicateGroup(g.files.map{f->
                    val name=byId[f.id]?.item?.originalName?:f.id
                    DuplicateTrack(f.id,name,null,name,f.uri,f.bytes,f.modified,f.sha,null,f.durationMs,null,null)
                },g.reason.name,g.reason.name)
            }
            DuplicateTrashSelection(groups,snapshot.selected,snapshot.selected,emptyMap())
        }
        val fingerprints=remember(snapshot){snapshot.result.groups.flatMap{it.files}.associateBy{it.id}}
        DuplicateTrashHost(request,byId,null,onAppTrash,
            onTrashed={uris->
                val next=VisualDuplicateSessionStore.removeConfirmed(context,snapshot.scannedAt,uris)
                if(next==null){error="Файл перемещён, но журнал не обновлён. Обновите поиск."; error("Не удалось сохранить результат корзины")}
                session=next
            },
            onClose={trash=null;onRefresh()},visual=fingerprints,
            catalogReady=ready && phonePermission && currentGeneration())
    }
    replacement?.let{request->
        AlertDialog(onDismissRequest={replacement=null},title={Text("Заменить незавершённый поиск?")},
            text={Text("Новая область: ${DuplicateFolderScope.label(request.keys)}. Предварительный отчёт прежнего поиска будет удалён. Завершённые отчёты, отпечатки и медиафайлы сохранятся.")},
            dismissButton={TextButton({replacement=null}){Text("Отмена")}},
            confirmButton={TextButton({
                replacement=null
                if(!saving&&trash==null&&startBlocker==null&&latestCatalog.source==request.source&&
                    latestEpoch==request.epoch&&DuplicatePrivacyGuard.generation(context)==request.epoch&&liveGeneration()&&
                    latestPermission&&(request.source!=LibrarySource.PHONE||
                        com.domenota.medialoader.ui.hasPhoneLibraryAccess(context,AppMode.GALLERY))&&
                    DuplicateFolderScope.filter(latestCatalog.scan,request.keys){it.item}.isNotEmpty()){
                    saving=true
                    scope.launch{
                        try{
                            val ok=VisualDuplicateSessionStore.discardPartial(context,request.epoch,request.token)
                            if(ok){storedPartial=null;showPartial=false}
                            else error="Не удалось заменить незавершённый поиск"
                            saving=false
                            if(ok)startScan(request.keys)
                        }finally{saving=false}
                    }
                }else error="Каталог изменился. Повторите выбор области."
            }){Text("Начать новый поиск")}})
    }
    if(clearJournalConfirm)AlertDialog(onDismissRequest={clearJournalConfirm=false},title={Text("Очистить журнал Галереи?")},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("Будут удалены все завершённые отчёты. Фото, видео и отпечатки останутся на устройстве.")
            if(interrupted)Row(verticalAlignment=Alignment.CenterVertically){
                Checkbox(clearPartialToo,{clearPartialToo=it})
                Text("Также удалить незавершённый поиск")
            }
        }},
        dismissButton={TextButton({clearJournalConfirm=false}){Text("Отмена")}},
        confirmButton={TextButton({
            clearJournalConfirm=false
            if(!status.running&&!status.committing&&!saving&&trash==null){
                saving=true
                val includePartial=clearPartialToo&&interrupted
                val actionEpoch=latestEpoch
                val actionToken=clearTargetToken
                scope.launch{
                    try{
                        if(actionToken!=null&&VisualDuplicateSessionStore.clearCompletedJournal(context,actionEpoch,includePartial,expectedToken=actionToken)){
                            if(includePartial){storedPartial=null;showPartial=false}
                            session=null;lastVerified=null;openedHistoryAt=null;preview=null;restored=false
                            history=VisualDuplicateSessionStore.history(context)
                        }else error="Не удалось очистить журнал"
                    }finally{saving=false}
                }
            }
        }){Text("Очистить",color=MaterialTheme.colorScheme.error)}})
    if(showPartial){
        androidx.activity.compose.BackHandler{preview=null;showPartial=false}
        Column(Modifier.fillMaxSize().padding(12.dp).testTag("visual-partial-results"),
            verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){
                IconButton({preview=null;showPartial=false}){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")}
                Text("Предварительные результаты",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
            }
            Text("Только просмотр и сравнение. Корзина доступна после завершения поиска.",
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            when{
                !phonePermission->Text("Нет доступа к фото и видео",color=MaterialTheme.colorScheme.error)
                partial==null&&!status.running&&!interrupted->Text("Поиск завершён или заменён. Вернитесь к итоговым результатам.")
                !partialReady->Text("Проверяю доступность файлов…")
                partialGroups.isEmpty()->Text("Доступных групп пока нет")
                else->{
                    Text(visualResultGroupCount(partialGroups.size),style=MaterialTheme.typography.titleMedium)
                    LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        items(partialGroups,key={it.files.first().id}){group->
                            Card(Modifier.fillMaxWidth()){
                                Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                                    Text("Точные копии · ${group.files.size} файлов")
                                    group.files.forEach{f->
                                        val row=byId[f.id]?.item
                                        if(row!=null)Row(Modifier.fillMaxWidth().clickable{preview=group to f.id},
                                            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                            DownloadThumbnail(f.uri,null,if(f.type==MediaType.PHOTO)MediaKind.IMAGE else MediaKind.VIDEO,Modifier.size(56.dp))
                                            Text(displayFileName(row),Modifier.weight(1f),maxLines=2)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        preview?.takeIf{partialReady}?.let{(group,id)->
            val currentGroup=partialGroups.firstOrNull{it.files.any{f->f.id==id}&&it.files.firstOrNull()?.id==group.files.firstOrNull()?.id}
            val pages=currentGroup?.files.orEmpty().mapNotNull{f->byId[f.id]?.takeIf{VisualDuplicateScanner.matchesCatalog(f,it)}}
            if(pages.size>=2)VisualDuplicatePreview(pages,id){preview=null}
        }
        return
    }
    // Replace the Activity-bounded NavHost content instead of opening a Dialog.
    // Gallery and Music now share precisely the same system-bar-safe picker.
    if (choosingScope) {
        com.domenota.medialoader.ui.components.DuplicateScanScopeScreen(
            rows = catalog.scan.map { it.item },
            mediaLabel = "Фото и видео",
            onDismiss = { choosingScope = false },
            onStart = { keys -> choosingScope = false; requestNewScan(keys) },
        )
        return
    }
    Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Row(Modifier.fillMaxWidth().heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onBack,enabled=trash==null){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")}
            Text("Дубликаты фото и видео",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,maxLines=2)
            OutlinedIconButton({journal=true},Modifier.size(44.dp),enabled=trash==null){
                Icon(Icons.Rounded.History,"Журнал Галереи")
            }
        }
        if(!status.running && (!reportReady||!loaded) && !stopped) GalleryScanProgress()
        if(!status.running && !phonePermission) Text("Нет доступа к фото и видео телефона",color=MaterialTheme.colorScheme.error)
        else if(!status.running && !ready && !stopped && shown==null) Row(verticalAlignment=Alignment.CenterVertically){
            // Keep a lightweight status while the latest MediaStore generation is checked.
            Text("Каталог обновляется",style=MaterialTheme.typography.bodySmall,modifier=Modifier.weight(1f))
            TextButton(onRefresh){Text("Обновить")}
        }
        else if(!status.running && shown==null) Text("Область поиска: ${if(catalog.source==LibrarySource.PHONE)"Доступные файлы телефона" else "Файлы ML"}",style=MaterialTheme.typography.bodySmall)
        if(status.running){
            val pauseBlocker=if(status.committing)"commit" else if(newSearchAfterPause)"pause_requested" else "none"
            DuplicateActionTrace("gallery", "pause", pauseBlocker=="none",pauseBlocker)
            DuplicateActionTrace("gallery", "stop", pauseBlocker=="none",pauseBlocker)
            Text(status.partial?.scopeLabel ?: DuplicateFolderScope.label(VisualDuplicateBackgroundScan.resumeScope(context)),
                style=MaterialTheme.typography.bodySmall)
            DuplicateScanPanel(status.phase,status.done,status.total,status.startedAt,status.phaseStartedAt,false,partial?.groups?.size?:0,exactBytes(partial?.groups.orEmpty()))
            status.coverage?.let { VisualCoverageSummary(it) }
            Button(onBack,Modifier.fillMaxWidth()){Text("Продолжить в фоне")}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton({if(!VisualDuplicateBackgroundScan.pause(context))error="Не удалось приостановить поиск"},enabled=!status.committing&&!newSearchAfterPause,modifier=Modifier.weight(1f)){Text("Пауза")}
                TextButton({VisualDuplicateBackgroundScan.cancel(context)},enabled=!status.committing&&!newSearchAfterPause){Text("Остановить")}
            }
            OutlinedButton(::chooseNewSearch,enabled=!status.committing&&!newSearchAfterPause&&phonePermission,
                modifier=Modifier.fillMaxWidth()){Text(if(newSearchAfterPause)"Приостанавливаю поиск…" else "Новый поиск")}
        }else{
            if(interrupted&&!sameScope)Text(if(resumeSource==null)"Проверяю область незавершённого поиска"
                else "Незавершённый поиск: ${if(resumeSource==LibrarySource.PHONE)"Доступные файлы телефона" else "Файлы ML"}",style=MaterialTheme.typography.bodySmall)
            val canStart=startBlocker==null
            val canResume=canStart&&interrupted&&sameScope
            val canShow=loaded&&phonePermission&&!saving&&trash==null&&
                session?.result?.privacyEpoch==privacyEpoch&&session?.result?.groups?.isNotEmpty()==true&&shown!=null
            DuplicateActionTrace("gallery", if(shown!=null||interrupted) "new_search" else "start",
                canStart, startBlocker?.code ?: "none")
            if(interrupted)DuplicateActionTrace("gallery","resume",canResume,
                startBlocker?.code ?: if(!sameScope)"scope_source_mismatch" else "none")
            if(startBlocker!=null && startBlocker!=VisualScanStartGate.Blocker.PERMISSION)
                Text(startBlocker.message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier=Modifier.testTag("visual-scan-blocker"))
            if(shown==null && stopped) {
                val resultsBlocker = when {
                    !phonePermission -> "permission"
                    !loaded -> "checkpoint_restore"
                    saving -> "checkpoint_write"
                    trash != null -> "trash"
                    !canShow -> "missing_current_epoch_report"
                    else -> "none"
                }
                DuplicateActionTrace("gallery", "results", canShow, resultsBlocker)
            }
            val openResults:()->Unit={
                if(!actionsReady){
                    // No empty or partial catalog may replace a saved result.
                    scope.launch{if(shown!=null)list.animateScrollToItem(0)}
                } else {
                    val actionEpoch=latestEpoch
                    scope.launch{
                        val previous=VisualDuplicateSessionStore.load(context)
                        val checked=withContext(Dispatchers.Default){previous?.reconcileById(byId)}
                        if(DuplicatePrivacyGuard.generation(context)==actionEpoch&&checked?.result?.privacyEpoch==actionEpoch){session=checked;restored=false}
                    }
                }
            }
            if(shown!=null){
                if(interrupted)Button({startScan(VisualDuplicateBackgroundScan.resumeScope(context),resume=true)},enabled=canResume,
                    modifier=Modifier.fillMaxWidth()){Text("Продолжить")}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Button(::chooseNewSearch,enabled=canStart,modifier=Modifier.weight(1f).heightIn(min=44.dp)){
                        Icon(Icons.Rounded.Search,null,Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Новый поиск",maxLines=1)
                    }
                    OutlinedButton({detailsExpanded=!detailsExpanded},
                        modifier=Modifier.weight(1f).heightIn(min=44.dp)){
                        Icon(if(detailsExpanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,null,Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Подробности",maxLines=1)
                    }
                }
            }else if(interrupted||stopped)VisualDuplicateStoppedPanel(
                canResume,canShow,{startScan(VisualDuplicateBackgroundScan.resumeScope(context),resume=true)},openResults,
                (status.coverage ?: storedPartial?.result?.coverage)?.takeIf{it.source==resumeSource},
                onNew=::chooseNewSearch,canNew=canStart,
                label=when(VisualDuplicateBackgroundScan.journalStatus(context)){
                    "Приостановлен"->"Поиск на паузе"
                    "Прерван"->"Поиск прерван"
                    else->"Поиск остановлен"
                }
            )
            else VisualDuplicateReadyPanel(canStart,canShow,::chooseNewSearch,openResults,hasPrevious=canShow)
        }
        listOfNotNull(status.error,error).distinct().forEach{message->
            com.domenota.medialoader.ui.components.DuplicateIssueNotice(message)
        }
        if(!loaded||!phonePermission)return@Column
        if(status.running||interrupted){
            DuplicateActionTrace("gallery","partial_results",partialCanShow,
                if(!phonePermission)"permission" else if(!partialReady)"report_pending" else if(partialGroups.isEmpty())"zero_groups" else "none")
            VisualPartialResultCard(partialCount,partialCanShow,unavailable=partialAbsent,onOpen=::openPartial)
        }
        if(status.running)return@Column
        if(shown==null){
            val pending=(status.session?:session)?.takeIf{it.result.privacyEpoch==privacyEpoch}
            Text(if(pending!=null)"Результаты сохранены: ${pending.result.groups.size} групп. Проверяю доступность файлов…"
                else if(interrupted)"Продолжите поиск или выберите новую область."
                else "Запустите поиск точных и похожих файлов.")
            return@Column
        }
        if(!actionsReady)Text("Обновляю каталог. Результат сохранён; выбор и корзина пока недоступны.",
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.fillMaxWidth().testTag("visual-result-summary"),
            verticalArrangement=Arrangement.spacedBy(3.dp)){
            Text("Результаты поиска",style=MaterialTheme.typography.titleMedium)
            Text(shown.result.scopeLabel,style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(visualResultGroupCount(shown.result.groups.size),
                style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.primary)
            shown.result.coverage?.let{
                Text("Проверено ${it.checked} файлов",style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(detailsExpanded){
            Column(Modifier.fillMaxWidth().testTag("visual-result-details"),
                verticalArrangement=Arrangement.spacedBy(4.dp)){
                shown.result.coverage?.let { VisualCoverageSummary(it) }
                Text("Пропущено: ${shown.result.skipped}. Выбрано: ${shown.selected.size}",
                    style=MaterialTheme.typography.bodySmall)
                Text("Каталог: ${if(catalog.source==LibrarySource.PHONE)"Телефон" else "Файлы ML"}",
                    style=MaterialTheme.typography.bodySmall)
            }
        }
        val kindCounts=remember(shown.result.groups) {
            DuplicateResultFilterPolicy.counts(shown.result.groups.map { it.reason })
        }
        val availableKinds=DuplicateResultFilterPolicy.available(VisualMatch.entries,kindCounts)
        val activeFilter=DuplicateResultFilterPolicy.active(filter,kindCounts)
        if(availableKinds.isNotEmpty()) {
            VisualDuplicateResultFilters(kindCounts,activeFilter,{ next ->
                filter=next
                scope.launch { list.scrollToItem(0) }
            })
        } else {
            Text("Совпадений не найдено",Modifier.padding(vertical=20.dp)
                .testTag("visual-empty-result"),style=MaterialTheme.typography.bodyMedium,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val exact=remember(shown.result.groups){VisualDuplicatePolicy.selectExact(shown.result.groups)}
        val visibleGroups=remember(shown.result.groups,activeFilter){
            shown.result.groups.filter{activeFilter==null||it.reason==activeFilter}
        }
        if(exact.isNotEmpty()||shown.selected.isNotEmpty())OutlinedButton({if(actionsReady)current?.let{persist(it.copy(selected=if(shown.selected==exact)emptySet() else exact))}},enabled=actionsReady&&!saving&&!status.running&&trash==null){Text(if(shown.selected==exact)"Снять выделение" else "Выделить точные")}
        LazyColumn(state=list,modifier=Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)){
            items(visibleGroups,key={it.files.first().id}){group->
                Card {
                    Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                        Text(when(group.reason){VisualMatch.EXACT->"Точные копии";VisualMatch.SIMILAR_PHOTO->"Похожие фотографии";VisualMatch.SIMILAR_VIDEO->"Похожие видео"},style=MaterialTheme.typography.titleMedium)
                        group.files.forEach{file->
                            // Thumbnail visibility is not tied to the temporary action
                            // gate. Retain a public, same-epoch result while MediaStore
                            // recalculates; still disable selection and deletion until
                            // the new index is verified.
                            val ref=byId[file.id]?.takeIf{VisualDuplicateScanner.matchesCatalog(file,it)}
                            val pendingIndex=!displayReady &&
                                shown.result.privacyEpoch==privacyEpoch && phonePermission && loaded
                            val canDisplay=ref!=null||pendingIndex
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                if(canDisplay) DownloadThumbnail(file.uri,null,if(file.type==MediaType.PHOTO)MediaKind.IMAGE else MediaKind.VIDEO,Modifier.size(76.dp).testTag("visual-thumbnail-media-${file.id}")
                                    .clickable(enabled=actionsReady&&ref!=null){preview=group to file.id})
                                else Box(Modifier.size(76.dp),contentAlignment=Alignment.Center){Icon(Icons.Rounded.Image,contentDescription=null,tint=MaterialTheme.colorScheme.onSurfaceVariant)}
                                Column(Modifier.weight(1f)){
                                    Text(ref?.let{displayFileName(it.item)} ?: if(pendingIndex)file.uri.substringAfterLast('/').takeIf{it.isNotBlank()} ?: "Проверка файла" else "Проверяю доступность файла",maxLines=2)
                                    Text(android.text.format.Formatter.formatFileSize(context,file.bytes)+" · ${file.width} × ${file.height}",style=MaterialTheme.typography.bodySmall)
                                    file.durationMs?.let{Text("${it/1000} с",style=MaterialTheme.typography.bodySmall)}
                                }
                                com.domenota.medialoader.ui.screens.organization.PickerMark(file.id in shown.selected,false,enabled=actionsReady&&!saving&&!status.running&&trash==null&&ref!=null&&(file.id in shown.selected||group.files.count{it.id !in shown.selected}>1)){
                                    val selected=if(file.id !in shown.selected)shown.selected+file.id else shown.selected-file.id
                                    if(actionsReady)current?.let{persist(it.copy(selected=selected,index=list.firstVisibleItemIndex,offset=list.firstVisibleItemScrollOffset))}
                                }
                            }
                        }
                    }
                }
            }
        }
        val selectedBytes=remember(shown.result.groups,shown.selected){
            shown.result.groups.sumOf{g->g.files.sumOf{if(it.id in shown.selected)it.bytes else 0L}}
        }
        if(shown.result.groups.isNotEmpty()) Button({
            if(actionsReady&&liveGeneration()&&DuplicatePrivacyGuard.generation(context)==latestEpoch)confirm=true
        },enabled=actionsReady&&shown.selected.isNotEmpty()&&!saving&&!status.running&&trash==null,
            modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("visual-result-trash")){
            Text("В корзину: ${shown.selected.size} файл(ов), ${android.text.format.Formatter.formatFileSize(context,selectedBytes)}")
        }
    }
    if(journal)DuplicateJournalDialog("Журнал Галереи",history.filter{it.result.privacyEpoch==privacyEpoch}.map{DuplicateJournalRow(it.scannedAt,it.result.groups.size,it.result.groups.sumOf{g->g.files.size})},
        storedPartial?.takeIf{(status.running||interrupted)&&it.result.privacyEpoch==privacyEpoch}
            ?.let{DuplicateJournalRow(it.scannedAt,it.result.groups.size,it.result.groups.sumOf{g->g.files.size},VisualDuplicateBackgroundScan.journalStatus(context),it.result.scopeLabel)},
        onOpen={at->journal=false;if(!status.running&&displayReady)history.firstOrNull{it.scannedAt==at}?.let{saved->scope.launch{val checked=withContext(Dispatchers.Default){saved.reconcileById(byId)};if(DuplicatePrivacyGuard.generation(context)==privacyEpoch){openedHistoryAt=at;persist(checked);restored=false}}}},
        onResume={journal=false;if(!status.running)startScan(VisualDuplicateBackgroundScan.resumeScope(context),resume=true)},onDismiss={journal=false},
        onPartial={journal=false;openPartial()},partialResultsEnabled=partialCanShow,
        resumeEnabled=startBlocker==null&&interrupted&&sameScope,
        onClear={journal=false;clearPartialToo=false;clearTargetToken=VisualDuplicateBackgroundScan.state.value.token;clearJournalConfirm=true},clearEnabled=(history.isNotEmpty()||interrupted)&&!status.running&&!status.committing&&!saving&&trash==null,
        onDelete={at->
            if(!status.running&&!status.committing&&!saving&&trash==null){
                saving=true
                scope.launch {
                    try {
                        val ok=withContext(Dispatchers.IO){
                            VisualDuplicateSessionStore.deleteCompleted(context,at)
                        }
                        if(ok){
                            VisualDuplicateBackgroundScan.forgetCompleted(at)
                            if(session?.scannedAt==at)session=null
                            if(lastVerified?.scannedAt==at)lastVerified=null
                            if(openedHistoryAt==at)openedHistoryAt=null
                            history=VisualDuplicateSessionStore.history(context)
                            // A partial checkpoint and its selection remain untouched.
                        }else error="Не удалось удалить выбранный отчёт"
                    }finally{saving=false}
                }
            }
        },
        deleteEnabled=!status.running&&!status.committing&&!saving&&trash==null)
    preview?.takeIf{displayReady}?.let{(g,id)->
        val pages=g.files.mapNotNull{f->byId[f.id]?.takeIf{VisualDuplicateScanner.matchesCatalog(f,it)}}
        if(pages.isNotEmpty())VisualDuplicatePreview(pages,id){preview=null}
    }
}

/** Search entry is compact and follows the same design as Music, without a long instruction. */
@Composable
internal fun VisualDuplicateReadyPanel(
    canStart:Boolean,canShowResults:Boolean,onStart:()->Unit,onResults:()->Unit,
    hasPrevious:Boolean=false
){
    Card(Modifier.fillMaxWidth()){
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                Icon(Icons.Rounded.ContentCopy,contentDescription=null,tint=MaterialTheme.colorScheme.primary)
                Column(verticalArrangement=Arrangement.spacedBy(3.dp)){
                    Text("Поиск дубликатов",style=MaterialTheme.typography.titleMedium)
                    Text("Точные и похожие фото и видео",color=MaterialTheme.colorScheme.onSurfaceVariant,
                        style=MaterialTheme.typography.bodySmall)
                }
            }
            Button(onStart,enabled=canStart,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){
                Text(if(hasPrevious)"Новый поиск" else "Начать поиск")
            }
            if(canShowResults)OutlinedButton(onResults,modifier=Modifier.fillMaxWidth()){
                Text("Последний поиск")
            }
        }
    }
}

/** Stopping never deletes the last completed report or the resumable fingerprints. */
@Composable
internal fun VisualDuplicateStoppedPanel(
    canResume:Boolean,canShowResults:Boolean,onResume:()->Unit,onResults:()->Unit,
    coverage:VisualScanCoverage?=null,onNew:(()->Unit)?=null,canNew:Boolean=canResume,
    label:String="Поиск остановлен"
){
    Card(Modifier.fillMaxWidth()){
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Icon(Icons.Rounded.Pause,contentDescription=null,tint=MaterialTheme.colorScheme.primary)
                Text(label,style=MaterialTheme.typography.titleMedium)
            }
            coverage?.let{ Text("Проверено: ${it.checked} из ${it.total}",
                color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                CompactStoppedAction("Продолжить",canResume,true,onResume,Modifier.weight(1f))
                CompactStoppedAction("Результаты",canShowResults,false,onResults,Modifier.weight(1f))
            }
            onNew?.let{OutlinedButton(it,enabled=canNew,modifier=Modifier.fillMaxWidth()){Text("Новый поиск")}}
        }
    }
}

@Composable
internal fun VisualPartialResultCard(count:Int?,canOpen:Boolean,unavailable:Boolean=false,onOpen:()->Unit){
    Card(Modifier.fillMaxWidth().testTag("visual-partial-card")){
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text(if(count!=null)visualResultGroupCount(count) else if(unavailable)"Предварительный отчёт пока не сохранён" else "Проверяю результаты…",
                style=MaterialTheme.typography.titleMedium)
            OutlinedButton(onOpen,enabled=canOpen&&count!=null&&count>0,modifier=Modifier.fillMaxWidth().testTag("visual-open-partial")){
                Text("Предварительные результаты")
            }
        }
    }
}

/** Keep both compact actions on a single row at narrow phone widths. */
@Composable
private fun CompactStoppedAction(label:String,enabled:Boolean,filled:Boolean,onClick:()->Unit,modifier:Modifier=Modifier){
    val scheme=MaterialTheme.colorScheme
    Surface(onClick=onClick,modifier=modifier,enabled=enabled,shape=CircleShape,
        color=if(filled){if(enabled)scheme.primary else scheme.onSurface.copy(alpha=0.12f)} else Color.Transparent,
        contentColor=if(!enabled)scheme.onSurface.copy(alpha=0.38f) else if(filled)scheme.onPrimary else scheme.primary,
        border=if(filled)null else androidx.compose.foundation.BorderStroke(1.dp,
            if(enabled)scheme.outline else scheme.onSurface.copy(alpha=0.12f))){
        Box(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(horizontal=10.dp,vertical=10.dp),
            contentAlignment=Alignment.Center){
            Text(label,modifier=Modifier.fillMaxWidth(),
                textAlign=androidx.compose.ui.text.style.TextAlign.Center,
                style=MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun VisualCoverageSummary(coverage:VisualScanCoverage){
    Text("${if(coverage.source==LibrarySource.PHONE)"Телефон" else "ML"} · охват: ${coverage.total} · проверено: ${coverage.checked}",style=MaterialTheme.typography.bodyMedium)
    Text("Из кеша: ${coverage.cached} · новые: ${coverage.computed} · ошибки: ${coverage.failed}",style=MaterialTheme.typography.bodySmall)
    if(coverage.aliases>0) Text("Совпадающие адреса: ${coverage.aliases}",style=MaterialTheme.typography.bodySmall)
    if(coverage.incompleteVisual>0) Text("Без полного визуального отпечатка: ${coverage.incompleteVisual}",style=MaterialTheme.typography.bodySmall)
}

@Composable
private fun VisualDuplicatePreview(pages:List<LibraryMediaRef>,initial:String,onClose:()->Unit){
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)){
        val pager=rememberPagerState(initialPage=pages.indexOfFirst{it.key==initial}.coerceAtLeast(0),pageCount={pages.size})
        var zoomed by remember{mutableStateOf(false)}
        LaunchedEffect(pager.settledPage){zoomed=false}
        Box(Modifier.fillMaxSize().background(Color.Black)){
            HorizontalPager(state=pager,userScrollEnabled=!zoomed,key={pages.getOrNull(it)?.key?:"preview-$it"},modifier=Modifier.fillMaxSize()){index->
                val row=pages.getOrNull(index)?.item?:return@HorizontalPager
                val uri=row.savedUri?.let(Uri::parse)?:return@HorizontalPager
                if(row.mediaType==MediaType.PHOTO)ZoomablePhoto(row,uri,index+1,pages.size,{value->if(pager.currentPage==index)zoomed=value},Modifier.fillMaxSize(),active=pager.settledPage==index)
                else if(pager.settledPage==index&&!pager.isScrollInProgress)AndroidView(factory={ctx->VideoView(ctx).apply{
                    setMediaController(MediaController(ctx).also{it.setAnchorView(this)});setVideoURI(uri);setOnPreparedListener{start()}
                }},modifier=Modifier.fillMaxSize(),onRelease={it.stopPlayback()})
            }
            Row(Modifier.fillMaxWidth().statusBarsPadding().background(Color.Black.copy(alpha=.6f)),verticalAlignment=Alignment.CenterVertically){
                IconButton(onClose){Icon(Icons.Rounded.Close,"Закрыть",tint=Color.White)}
                Text("${pager.currentPage+1} / ${pages.size}",color=Color.White)
            }
        }
    }
}

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
import com.domenota.medialoader.ui.components.DuplicateScanPanel
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
    var filter by remember{mutableStateOf<VisualMatch?>(null)}
    var preview by remember{mutableStateOf<Pair<VisualDuplicateGroup,String>?>(null)}
    var choosingScope by remember { mutableStateOf(false) }
    var openedHistoryAt by remember { mutableStateOf<Long?>(null) }
    var detailsExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(journal,status.running,privacyEpoch){history=VisualDuplicateSessionStore.history(context);storedPartial=VisualDuplicateSessionStore.partial(context)}
    LaunchedEffect(privacyEpoch){preview=null;journal=false}
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
    val latestSession by rememberUpdatedState(session)
    val latestCatalog by rememberUpdatedState(catalog)
    val latestReady by rememberUpdatedState(ready && phonePermission)
    val latestEpoch by rememberUpdatedState(privacyEpoch)
    val liveGeneration by rememberUpdatedState(currentGeneration)
    fun startScan(keys: Set<String>? = null){
        if(latestReady && liveGeneration() && DuplicatePrivacyGuard.generation(context)==latestEpoch) {
            openedHistoryAt=null
            val currentCatalog=latestCatalog
            if(currentCatalog.source==LibrarySource.PHONE && !com.domenota.medialoader.ui.hasPhoneLibraryAccess(context,AppMode.GALLERY))return
            val scanned=DuplicateFolderScope.filter(currentCatalog.scan,keys){it.item}
            if(scanned.isEmpty()) { error="В выбранных папках нет доступных файлов";return }
            VisualDuplicateBackgroundScan.start(context,scanned,currentCatalog.source,latestEpoch,keys)
        }
    }
    val partial=(if(status.running)status.partial else status.partial?:storedPartial?.result)?.takeIf{it.privacyEpoch==privacyEpoch}
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
            Text(status.partial?.scopeLabel ?: DuplicateFolderScope.label(VisualDuplicateBackgroundScan.resumeScope(context)),
                style=MaterialTheme.typography.bodySmall)
            DuplicateScanPanel(status.phase,status.done,status.total,status.startedAt,status.phaseStartedAt,false,partial?.groups?.size?:0,exactBytes(partial?.groups.orEmpty()))
            status.coverage?.let { VisualCoverageSummary(it) }
            Button(onBack,Modifier.fillMaxWidth()){Text("Продолжить в фоне")}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton({VisualDuplicateBackgroundScan.pause(context)},enabled=!status.committing,modifier=Modifier.weight(1f)){Text("Пауза")}
                TextButton({VisualDuplicateBackgroundScan.cancel(context)},enabled=!status.committing){Text("Остановить")}
            }
        }else{
            val previousScope=storedPartial?.result?.coverage?.source
            val sameScope=previousScope==null||previousScope==catalog.source
            if(!sameScope)Text("Незавершённый поиск: ${if(previousScope==LibrarySource.PHONE)"Доступные файлы телефона" else "Файлы ML"}",style=MaterialTheme.typography.bodySmall)
            val canStart=ready&&phonePermission&&loaded&&!saving&&trash==null&&catalog.scan.isNotEmpty()
            val canShow=loaded&&phonePermission&&!saving&&trash==null&&
                (session?.result?.privacyEpoch==privacyEpoch||partial!=null)
            val openResults:()->Unit={
                if(session==null&&partial!=null)showPartial=true
                else if(!actionsReady){
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
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Button({choosingScope=true},enabled=canStart,modifier=Modifier.weight(1f).heightIn(min=44.dp)){
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
            }else if(stopped)VisualDuplicateStoppedPanel(
                canStart,canShow,{startScan(VisualDuplicateBackgroundScan.resumeScope(context))},openResults,
                (status.coverage ?: storedPartial?.result?.coverage)?.takeIf{it.source==catalog.source}
            )
            else VisualDuplicateReadyPanel(canStart,canShow,{choosingScope=true},openResults,
                hasPrevious=canShow || (sameScope&&VisualDuplicateBackgroundScan.interrupted(context)))
        }
        listOfNotNull(status.error,error).distinct().forEach{message->
            com.domenota.medialoader.ui.components.DuplicateIssueNotice(message)
        }
        if(!loaded||!phonePermission)return@Column
        if(status.running&&partial!=null&&displayReady){
            Text("Предварительные результаты · удаление после завершения")
            LazyColumn(Modifier.weight(1f)){items(partial.groups,key={it.files.first().id}){g->
                Row(verticalAlignment=Alignment.CenterVertically){
                    g.files.take(3).forEach{f->if(byId[f.id]?.let{VisualDuplicateScanner.matchesCatalog(f,it)}==true)DownloadThumbnail(f.uri,null,if(f.type==MediaType.PHOTO)MediaKind.IMAGE else MediaKind.VIDEO,Modifier.size(54.dp).clickable{preview=g to f.id})}
                    Text("${g.files.size} файлов",Modifier.padding(8.dp))
                }
            }};return@Column
        }
        if(shown==null){
            val pending=(status.session?:session)?.takeIf{it.result.privacyEpoch==privacyEpoch}
            Text(if(pending!=null)"Результаты сохранены: ${pending.result.groups.size} групп. Проверяю доступность файлов…"
                else if(partial!=null)"Сохранено предварительно: ${partial.groups.size} групп. Продолжите поиск."
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
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            FilterChip(filter==null,{filter=null},{Text("Все")})
            VisualMatch.entries.forEach{k->FilterChip(filter==k,{filter=k},{Text(when(k){VisualMatch.EXACT->"Точные";VisualMatch.SIMILAR_PHOTO->"Похожие фото";VisualMatch.SIMILAR_VIDEO->"Видео"})})}
        }
        val exact=VisualDuplicatePolicy.selectExact(shown.result.groups)
        if(exact.isNotEmpty()||shown.selected.isNotEmpty())OutlinedButton({if(actionsReady)current?.let{persist(it.copy(selected=if(shown.selected==exact)emptySet() else exact))}},enabled=actionsReady&&!saving&&!status.running&&trash==null){Text(if(shown.selected==exact)"Снять выделение" else "Выделить точные")}
        LazyColumn(state=list,modifier=Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)){
            items(shown.result.groups.filter{filter==null||it.reason==filter},key={it.files.first().id}){group->
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
        val selectedBytes=shown.result.groups.sumOf{g->g.files.filter{it.id in shown.selected}.sumOf{it.bytes}}
        Button({if(actionsReady&&liveGeneration()&&DuplicatePrivacyGuard.generation(context)==latestEpoch)confirm=true},enabled=actionsReady&&shown.selected.isNotEmpty()&&!saving&&!status.running&&trash==null,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("visual-result-trash")){
            Text("В корзину: ${shown.selected.size} файл(ов), ${android.text.format.Formatter.formatFileSize(context,selectedBytes)}")
        }
    }
    if(journal)DuplicateJournalDialog("Журнал Галереи",history.map{DuplicateJournalRow(it.scannedAt,it.result.groups.size,it.result.groups.sumOf{g->g.files.size})},
        storedPartial?.let{DuplicateJournalRow(it.scannedAt,it.result.groups.size,it.result.groups.sumOf{g->g.files.size} ,VisualDuplicateBackgroundScan.journalStatus(context))},
        onOpen={at->journal=false;if(!status.running&&displayReady)history.firstOrNull{it.scannedAt==at}?.let{saved->scope.launch{val checked=withContext(Dispatchers.Default){saved.reconcileById(byId)};if(DuplicatePrivacyGuard.generation(context)==privacyEpoch){openedHistoryAt=at;persist(checked);restored=false}}}},
        onResume={journal=false;if(!status.running)startScan(VisualDuplicateBackgroundScan.resumeScope(context))},onDismiss={journal=false},
        onPartial={journal=false;showPartial=true},
        resumeEnabled=!status.running&&ready&&phonePermission&&catalog.scan.isNotEmpty(),
        onDelete={at->
            if(!status.running&&!status.committing&&!saving&&trash==null){
                saving=true
                scope.launch {
                    try {
                        val ok=withContext(Dispatchers.IO){
                            VisualDuplicateSessionStore.deleteCompleted(context,at)
                        }
                        if(ok){
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
    if(choosingScope)com.domenota.medialoader.ui.components.DuplicateScanScopeDialog(
        rows=catalog.scan.map{it.item},mediaLabel="Фото и видео",
        onDismiss={choosingScope=false},
        onStart={keys->choosingScope=false;startScan(keys)},
    )
    if(showPartial&&displayReady&&partial!=null)AlertDialog(onDismissRequest={showPartial=false},title={Text("Предварительные результаты")},
        text={LazyColumn(Modifier.heightIn(max=400.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            item{Text("Только просмотр. Удаление доступно после завершения.")}
            items(partial.groups,key={it.files.first().id}){g->Column{
                Text("Точные копии · ${g.files.size} файлов")
                g.files.forEach{f->byId[f.id]?.takeIf{VisualDuplicateScanner.matchesCatalog(f,it)}?.let{row->Text(displayFileName(row.item),style=MaterialTheme.typography.bodySmall)}}
            }}
        }},confirmButton={TextButton({showPartial=false}){Text("Закрыть")}})
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
    coverage:VisualScanCoverage?=null
){
    Card(Modifier.fillMaxWidth()){
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Icon(Icons.Rounded.Pause,contentDescription=null,tint=MaterialTheme.colorScheme.primary)
                Text("Поиск остановлен",style=MaterialTheme.typography.titleMedium)
            }
            coverage?.let{ Text("Проверено: ${it.checked} из ${it.total}",
                color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                CompactStoppedAction("Продолжить",canResume,true,onResume)
                CompactStoppedAction("Результаты",canShowResults,false,onResults)
            }
        }
    }
}

/** Keep both compact actions on a single row at narrow phone widths. */
@Composable
private fun CompactStoppedAction(label:String,enabled:Boolean,filled:Boolean,onClick:()->Unit){
    val scheme=MaterialTheme.colorScheme
    Surface(onClick=onClick,enabled=enabled,shape=CircleShape,
        color=if(filled){if(enabled)scheme.primary else scheme.onSurface.copy(alpha=0.12f)} else Color.Transparent,
        contentColor=if(!enabled)scheme.onSurface.copy(alpha=0.38f) else if(filled)scheme.onPrimary else scheme.primary,
        border=if(filled)null else androidx.compose.foundation.BorderStroke(1.dp,
            if(enabled)scheme.outline else scheme.onSurface.copy(alpha=0.12f))){
        Text(label,modifier=Modifier.padding(horizontal=10.dp,vertical=10.dp).widthIn(min=110.dp),
            maxLines=1,softWrap=true,style=MaterialTheme.typography.labelSmall)
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

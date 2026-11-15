package com.domenota.medialoader.ui.screens.gallery

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

@Composable
internal fun VisualDuplicatesScreen(refs:List<LibraryMediaRef>,ready:Boolean,onBack:()->Unit,onAppTrash:suspend (String,String)->Unit,onRefresh:()->Unit) {
    val context=LocalContext.current
    val status by VisualDuplicateBackgroundScan.state.collectAsStateWithLifecycle()
    val privacyEpoch by DuplicatePrivacyGuard.changes.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var session by remember { mutableStateOf<VisualDuplicateSession?>(null) }
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
    LaunchedEffect(journal,status.running,privacyEpoch){history=VisualDuplicateSessionStore.history(context);storedPartial=VisualDuplicateSessionStore.partial(context)}
    LaunchedEffect(privacyEpoch){preview=null;journal=false}
    val list=rememberLazyListState()
    val byId=remember(refs,ready){if(ready)refs.groupBy{it.key}.mapNotNull{(id,rows)->rows.singleOrNull()?.let{id to it}}.toMap() else emptyMap()}
    // Render only a projection validated against the current complete public catalog.
    val current=remember(session,refs,ready,privacyEpoch){if(ready)session?.takeIf{it.result.privacyEpoch==privacyEpoch}?.reconcile(refs) else null}
    val latestSession by rememberUpdatedState(session)
    val partial=(if(status.running)status.partial else status.partial?:storedPartial?.result)?.takeIf{it.privacyEpoch==privacyEpoch}
    fun exactBytes(value:List<VisualDuplicateGroup>)=value.filter{it.reason==VisualMatch.EXACT}.sumOf{g->g.files.sumOf{it.bytes}-(g.files.firstOrNull()?.bytes?:0L)}
    LaunchedEffect(privacyEpoch) {
        session=VisualDuplicateSessionStore.load(context)
        loaded=true
    }
    LaunchedEffect(status.session,loaded,trash) {
        if(loaded&&trash==null) status.session?.let { fresh ->
            if(fresh.scannedAt!=session?.scannedAt){session=fresh;restored=false}
        }
    }
    LaunchedEffect(ready,refs,loaded,status.running,trash) {
        if(ready&&loaded&&!status.running&&trash==null) session=session?.reconcile(refs)
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
        session=next.normalized()
        saving=true
        scope.launch {
            try{if(!VisualDuplicateSessionStore.save(context,next))error="Не удалось сохранить выбор"}
            finally{saving=false}
        }
    }
    if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("Переместить выбранные файлы в корзину?")},
        text={Text("Похожие файлы могут различаться. Сохраняемый экземпляр будет повторно проверен. Окончательное удаление не выполняется.")},
        confirmButton={TextButton({confirm=false;trash=current?.takeIf{it.selected.isNotEmpty()}}){Text("Переместить")}},
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
            onClose={trash=null;onRefresh()},visual=fingerprints)
    }
    Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){
            IconButton(onBack,enabled=trash==null){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")}
            Text("Дубликаты фото и видео",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,maxLines=2)
            IconButton({journal=true},enabled=trash==null){Icon(Icons.Rounded.History,"Журнал Галереи")}
        }
        if(!ready||!loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(status.running){
            DuplicateScanPanel(status.phase,status.done,status.total,status.startedAt,status.phaseStartedAt,false,partial?.groups?.size?:0,exactBytes(partial?.groups.orEmpty()))
            Button(onBack,Modifier.fillMaxWidth()){Text("Продолжить в фоне")}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton({VisualDuplicateBackgroundScan.pause(context)},enabled=!status.committing,modifier=Modifier.weight(1f)){Text("Пауза")}
                TextButton({VisualDuplicateBackgroundScan.cancel(context)},enabled=!status.committing){Text("Остановить")}
            }
        }else{
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button({VisualDuplicateBackgroundScan.start(context,refs)},enabled=ready&&loaded&&!saving&&trash==null){Text(if(VisualDuplicateBackgroundScan.interrupted(context))"Продолжить поиск" else "Новый поиск")}
                OutlinedButton({scope.launch{
                    val previous=VisualDuplicateSessionStore.load(context)
                    session=previous?.reconcile(refs);restored=false
                }},enabled=ready&&loaded&&!saving&&trash==null){Text("Последний поиск")}
            }
        }
        status.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        if(!ready||!loaded)return@Column
        if(status.running&&partial!=null){
            Text("Предварительные результаты · удаление после завершения")
            LazyColumn(Modifier.weight(1f)){items(partial.groups,key={it.files.first().id}){g->
                Row(verticalAlignment=Alignment.CenterVertically){
                    g.files.take(3).forEach{f->if(byId[f.id]?.let{VisualDuplicateScanner.matchesCatalog(f,it)}==true)DownloadThumbnail(f.uri,null,if(f.type==MediaType.PHOTO)MediaKind.IMAGE else MediaKind.VIDEO,Modifier.size(54.dp).clickable{preview=g to f.id})}
                    Text("${g.files.size} файлов",Modifier.padding(8.dp))
                }
            }};return@Column
        }
        if(current==null){Text(if(partial!=null)"Сохранено предварительно: ${partial.groups.size} групп. Продолжите поиск." else "Запустите поиск точных и похожих файлов.");return@Column}
        Text("Групп: ${current.result.groups.size} · пропущено: ${current.result.skipped} · выбрано: ${current.selected.size}")
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            FilterChip(filter==null,{filter=null},{Text("Все")})
            VisualMatch.entries.forEach{k->FilterChip(filter==k,{filter=k},{Text(when(k){VisualMatch.EXACT->"Точные";VisualMatch.SIMILAR_PHOTO->"Похожие фото";VisualMatch.SIMILAR_VIDEO->"Видео"})})}
        }
        val exact=VisualDuplicatePolicy.selectExact(current.result.groups)
        if(exact.isNotEmpty()||current.selected.isNotEmpty())OutlinedButton({persist(current.copy(selected=if(current.selected==exact)emptySet() else exact))},enabled=!saving&&!status.running&&trash==null){Text(if(current.selected==exact)"Снять выделение" else "Выделить точные")}
        Text("Похожие файлы могут различаться. Проверьте каждый экземпляр.",style=MaterialTheme.typography.bodySmall)
        LazyColumn(state=list,modifier=Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)){
            items(current.result.groups.filter{filter==null||it.reason==filter},key={it.files.first().id}){group->
                Card {
                    Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                        Text(when(group.reason){VisualMatch.EXACT->"Точные копии";VisualMatch.SIMILAR_PHOTO->"Похожие фотографии";VisualMatch.SIMILAR_VIDEO->"Похожие видео"},style=MaterialTheme.typography.titleMedium)
                        group.files.forEach{file->
                            val ref=byId[file.id]
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                DownloadThumbnail(file.uri,null,if(file.type==MediaType.PHOTO)MediaKind.IMAGE else MediaKind.VIDEO,Modifier.size(76.dp).clickable(enabled=ref!=null){preview=group to file.id})
                                Column(Modifier.weight(1f)){
                                    Text(ref?.item?.originalName?:file.id,maxLines=2)
                                    Text(android.text.format.Formatter.formatFileSize(context,file.bytes)+" · ${file.width} × ${file.height}",style=MaterialTheme.typography.bodySmall)
                                    file.durationMs?.let{Text("${it/1000} с",style=MaterialTheme.typography.bodySmall)}
                                }
                                com.domenota.medialoader.ui.screens.organization.PickerMark(file.id in current.selected,false,enabled=ready&&!saving&&!status.running&&trash==null&&ref!=null&&(file.id in current.selected||group.files.count{it.id !in current.selected}>1)){
                                    val selected=if(file.id !in current.selected)current.selected+file.id else current.selected-file.id
                                    persist(current.copy(selected=selected,index=list.firstVisibleItemIndex,offset=list.firstVisibleItemScrollOffset))
                                }
                            }
                        }
                    }
                }
            }
        }
        val selectedBytes=current.result.groups.sumOf{g->g.files.filter{it.id in current.selected}.sumOf{it.bytes}}
        Button({confirm=true},enabled=current.selected.isNotEmpty()&&!saving&&!status.running&&trash==null,modifier=Modifier.fillMaxWidth()){
            Text("В корзину: ${current.selected.size} · ${android.text.format.Formatter.formatFileSize(context,selectedBytes)}")
        }
    }
    if(journal)DuplicateJournalDialog("Журнал Галереи",history.map{DuplicateJournalRow(it.scannedAt,it.result.groups.size,it.result.groups.sumOf{g->g.files.size})},
        storedPartial?.let{DuplicateJournalRow(it.scannedAt,it.result.groups.size,it.result.groups.sumOf{g->g.files.size} ,VisualDuplicateBackgroundScan.journalStatus(context))},
        onOpen={at->journal=false;if(!status.running&&ready)history.firstOrNull{it.scannedAt==at}?.let{persist(it.reconcile(refs));restored=false}},
        onResume={journal=false;if(!status.running&&ready)VisualDuplicateBackgroundScan.start(context,refs)},onDismiss={journal=false},
        onPartial={journal=false;showPartial=true},resumeEnabled=!status.running&&ready)
    if(showPartial&&partial!=null)AlertDialog(onDismissRequest={showPartial=false},title={Text("Предварительные результаты")},
        text={LazyColumn(Modifier.heightIn(max=400.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            item{Text("Только просмотр. Удаление доступно после завершения.")}
            items(partial.groups,key={it.files.first().id}){g->Column{
                Text("Точные копии · ${g.files.size} файлов")
                g.files.forEach{f->byId[f.id]?.takeIf{VisualDuplicateScanner.matchesCatalog(f,it)}?.let{row->Text(row.item.originalName,style=MaterialTheme.typography.bodySmall)}}
            }}
        }},confirmButton={TextButton({showPartial=false}){Text("Закрыть")}})
    preview?.let{(g,id)->
        val pages=g.files.mapNotNull{f->byId[f.id]?.takeIf{VisualDuplicateScanner.matchesCatalog(f,it)}}
        if(pages.isNotEmpty())VisualDuplicatePreview(pages,id){preview=null}
    }
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

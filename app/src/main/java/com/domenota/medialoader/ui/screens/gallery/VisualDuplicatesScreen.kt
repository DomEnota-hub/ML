package com.domenota.medialoader.ui.screens.gallery

import androidx.compose.foundation.layout.*
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
    val scope=rememberCoroutineScope()
    var session by remember { mutableStateOf<VisualDuplicateSession?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var restored by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var trash by remember { mutableStateOf<VisualDuplicateSession?>(null) }
    val list=rememberLazyListState()
    val byId=remember(refs,ready){if(ready)refs.groupBy{it.key}.mapNotNull{(id,rows)->rows.singleOrNull()?.let{id to it}}.toMap() else emptyMap()}
    // Render only a projection validated against the current complete public catalog.
    val current=remember(session,refs,ready){if(ready)session?.reconcile(refs) else null}
    val latestSession by rememberUpdatedState(session)
    LaunchedEffect(Unit) {
        session=VisualDuplicateSessionStore.load(context)
        loaded=true
    }
    LaunchedEffect(status.session,loaded) {
        if(loaded) status.session?.let { fresh ->
            if(fresh.scannedAt!=session?.scannedAt){session=fresh;restored=false}
        }
    }
    LaunchedEffect(ready,refs,loaded,status.running) {
        if(ready&&loaded&&!status.running) session=session?.reconcile(refs)
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
                val previous=latestSession ?: snapshot
                val next=previous.removeConfirmed(uris)
                session=next
                check(VisualDuplicateSessionStore.save(context,next)){"Не удалось сохранить результат корзины"}
            },
            onClose={trash=null;onRefresh()},visual=fingerprints)
    }
    Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){
            TextButton(onBack,enabled=trash==null){Text("Назад")}
            Text("Дубликаты фото и видео",style=MaterialTheme.typography.titleLarge)
        }
        if(!ready||!loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(status.running){
            Text("Проверено ${status.done} из ${status.total}")
            LinearProgressIndicator(progress={if(status.total>0)status.done.toFloat()/status.total else 0f},modifier=Modifier.fillMaxWidth())
            OutlinedButton({VisualDuplicateBackgroundScan.cancel(context)},enabled=!status.committing){Text("Отменить поиск")}
        }else{
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button({VisualDuplicateBackgroundScan.start(context,refs)},enabled=ready&&loaded&&!saving&&trash==null){Text("Новый поиск")}
                OutlinedButton({scope.launch{
                    val previous=VisualDuplicateSessionStore.load(context)
                    session=previous?.reconcile(refs);restored=false
                }},enabled=ready&&loaded&&!saving){Text("Последний поиск")}
            }
        }
        status.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        if(!ready||!loaded)return@Column
        if(current==null){Text("Запустите поиск точных и похожих файлов.");return@Column}
        Text("Групп: ${current.result.groups.size} · пропущено: ${current.result.skipped} · выбрано: ${current.selected.size}")
        Button({confirm=true},enabled=current.selected.isNotEmpty()&&!saving&&!status.running&&trash==null,modifier=Modifier.fillMaxWidth()){
            Text("В корзину: ${current.selected.size}")
        }
        Text("Похожие файлы могут различаться. Проверьте каждый экземпляр.",style=MaterialTheme.typography.bodySmall)
        LazyColumn(state=list,modifier=Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)){
            items(current.result.groups,key={it.files.first().id}){group->
                Card {
                    Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                        Text(when(group.reason){VisualMatch.EXACT->"Точные копии";VisualMatch.SIMILAR_PHOTO->"Похожие фотографии";VisualMatch.SIMILAR_VIDEO->"Похожие видео"},style=MaterialTheme.typography.titleMedium)
                        group.files.forEach{file->
                            val ref=byId[file.id]
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                DownloadThumbnail(file.uri,ref?.item?.previewUrl,if(file.type==MediaType.PHOTO)MediaKind.IMAGE else MediaKind.VIDEO,Modifier.size(76.dp))
                                Column(Modifier.weight(1f)){
                                    Text(ref?.item?.originalName?:file.id,maxLines=2)
                                    Text(android.text.format.Formatter.formatFileSize(context,file.bytes)+" · ${file.width} × ${file.height}",style=MaterialTheme.typography.bodySmall)
                                    file.durationMs?.let{Text("${it/1000} с",style=MaterialTheme.typography.bodySmall)}
                                }
                                Checkbox(file.id in current.selected,{checked->
                                    val selected=if(checked)current.selected+file.id else current.selected-file.id
                                    persist(current.copy(selected=selected,index=list.firstVisibleItemIndex,offset=list.firstVisibleItemScrollOffset))
                                },enabled=ready&&!saving&&!status.running&&trash==null&&ref!=null&&(file.id in current.selected||group.files.count{it.id !in current.selected}>1))
                            }
                        }
                    }
                }
            }
        }
    }
}

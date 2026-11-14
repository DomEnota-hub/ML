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
import com.domenota.medialoader.ui.model.MediaKind
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
internal fun VisualDuplicatesScreen(refs:List<LibraryMediaRef>,ready:Boolean,onBack:()->Unit) {
    val context=LocalContext.current
    val status by VisualDuplicateBackgroundScan.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var session by remember { mutableStateOf<VisualDuplicateSession?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var restored by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val list=rememberLazyListState()
    val byId=remember(refs){refs.associateBy{it.key}}
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
    LaunchedEffect(session?.scannedAt,loaded,ready) {
        if(loaded&&ready&&!restored){
            session?.let{list.scrollToItem(it.index,it.offset)}
            restored=true
        }
    }
    LaunchedEffect(list,restored,status.running) {
        if(restored&&!status.running) snapshotFlow{list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset}.collectLatest{(index,offset)->
            session=session?.copy(index=index,offset=offset)
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
    Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){
            TextButton(onBack){Text("Назад")}
            Text("Дубликаты фото и видео",style=MaterialTheme.typography.titleLarge)
        }
        if(!ready||!loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(status.running){
            Text("Проверено ${status.done} из ${status.total}")
            LinearProgressIndicator(progress={if(status.total>0)status.done.toFloat()/status.total else 0f},modifier=Modifier.fillMaxWidth())
            OutlinedButton({VisualDuplicateBackgroundScan.cancel(context)},enabled=!status.committing){Text("Отменить поиск")}
        }else{
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button({VisualDuplicateBackgroundScan.start(context,refs)},enabled=ready&&loaded&&!saving){Text("Новый поиск")}
                OutlinedButton({scope.launch{
                    val previous=VisualDuplicateSessionStore.load(context)
                    session=previous?.reconcile(refs);restored=false
                    if(previous!=null){list.scrollToItem(session?.index?:0,session?.offset?:0);restored=true}
                }},enabled=ready&&loaded&&!saving){Text("Последний поиск")}
            }
        }
        status.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        val current=session
        if(current==null){Text("Запустите поиск точных и похожих файлов.");return@Column}
        Text("Групп: ${current.result.groups.size} · пропущено: ${current.result.skipped} · выбрано: ${current.selected.size}")
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
                                },enabled=ready&&!saving&&!status.running&&ref!=null&&(file.id in current.selected||group.files.count{it.id !in current.selected}>1))
                            }
                        }
                    }
                }
            }
        }
    }
}

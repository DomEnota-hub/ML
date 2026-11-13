package com.domenota.medialoader.ui.screens.organization

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.database.*
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.*
import com.domenota.medialoader.ui.components.DownloadThumbnail
import com.domenota.medialoader.ui.model.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun MediaType.caption()=when(this){MediaType.PHOTO->"Фото";MediaType.VIDEO->"Видео";MediaType.AUDIO->"Музыка"}
private fun MediaType.kind()=when(this){MediaType.PHOTO->MediaKind.IMAGE;MediaType.VIDEO->MediaKind.VIDEO;MediaType.AUDIO->MediaKind.AUDIO}
private fun date(at:Long)=Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))

@Composable
internal fun OrganizationEntry(title:String,description:String,onOpen:()->Unit,modifier:Modifier=Modifier) {
    Surface(modifier.fillMaxWidth().clickable(onClick=onOpen),shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceVariant){
        Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
            Icon(Icons.Rounded.CollectionsBookmark,null,Modifier.size(32.dp),tint=MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.titleMedium);Text(description,style=MaterialTheme.typography.bodySmall)}
            Icon(Icons.Rounded.ChevronRight,null)
        }
    }
}
@Composable
internal fun CollectionGalleryStrip(collections:List<MediaCollection>,refs:List<LibraryMediaRef>,onOpen:(String?)->Unit) {
    // Only the two displayed cards resolve member identities; opening the full
    // screen performs the complete projection on a worker thread.
    val shown=remember(collections){collections.take(2)}
    val covers by produceState(emptyMap<String,LibraryMediaRef>(),shown,refs) {
        val projection = withContext(Dispatchers.Default) { MediaOrganizationPolicy.resolve(shown.flatMap { it.members }, refs) }
        this.value = projection
    }
    Column(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Text("Коллекции",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
            TextButton({onOpen(null)}){Text("Все коллекции")}
        }
        if(shown.isEmpty()) OrganizationEntry("Новая коллекция","Фото, видео, музыка и ссылки",{onOpen(null)})
        else Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            shown.forEach { collection ->
                val live=remember(refs){refs.associateBy{it.key}}
                val displayed=collection.members.mapNotNull{member->covers[member.id]?.let{candidate->live[candidate.key]?.item}}
                val cover=displayed.firstOrNull()
                Surface(Modifier.weight(1f).clickable{onOpen(collection.record.id)},shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant){
                    Column(Modifier.padding(8.dp)){
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)),contentAlignment=Alignment.Center){
                            if(cover!=null)DownloadThumbnail(cover.savedUri,cover.previewUrl,cover.mediaType.kind(),Modifier.fillMaxSize())
                            else Icon(Icons.Rounded.CollectionsBookmark,null,Modifier.size(42.dp),tint=MaterialTheme.colorScheme.primary)
                        }
                        Text(collection.record.name,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                        Text("Фото: ${displayed.count{it.mediaType==MediaType.PHOTO}} · Видео: ${displayed.count{it.mediaType==MediaType.VIDEO}}",style=MaterialTheme.typography.labelSmall)
                        Text("Музыка: ${displayed.count{it.mediaType==MediaType.AUDIO}} · Ссылки: ${collection.members.count{it.link!=null}}",style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
@Composable
private fun Header(title:String,onBack:()->Unit,actions:@Composable RowScope.()->Unit={}) {
    Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
        IconButton(onBack){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")}
        Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
        actions()
    }
}
@Composable
private fun MediaLine(row:DownloadEntity,onOpen:()->Unit,actions:@Composable RowScope.()->Unit={}) {
    Row(Modifier.fillMaxWidth().padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
        DownloadThumbnail(row.savedUri,row.previewUrl,row.mediaType.kind(),Modifier.size(54.dp).clip(RoundedCornerShape(9.dp)))
        Column(Modifier.weight(1f).clickable(onClick=onOpen)){
            Text(row.originalName,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyLarge)
            Text(row.mediaType.caption(),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}
@Composable
private fun ValueDialog(title:String,initial:String,label:String,onDismiss:()->Unit,onSave:suspend (String)->Unit) {
    var input by remember {mutableStateOf(initial)}
    var error by remember {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text(title)},text={
        Column {OutlinedTextField(input,{input=it},label={Text(label)},singleLine=true,enabled=!busy)
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}}
    },confirmButton={TextButton(enabled=!busy&&input.isNotBlank(),onClick={scope.launch{
        busy=true
        try{onSave(input);onDismiss()}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"Не удалось сохранить"}finally{busy=false}
    }}){Text("Сохранить")}},dismissButton={TextButton(onDismiss,enabled=!busy){Text("Отмена")}})
}

@Composable
internal fun CollectionsScreen(collections:List<MediaCollection>,refs:List<LibraryMediaRef>,repository:MediaOrganizationRepository,
    initialCollection:String?,audioOnly:Boolean,initialAdd:List<String>,initialLink:String?,ready:Boolean,onBack:()->Unit,
    onOpen:(String,List<String>)->Unit,onPlay:(List<String>)->Unit,onLink:(String)->Unit,onPassport:(String)->Unit,
    onAddComplete:()->Unit) {
    var selected by rememberSaveable(initialCollection){mutableStateOf(initialCollection)}
    var filter by rememberSaveable(audioOnly){mutableStateOf(if(audioOnly)"Музыка" else "Всё")}
    var create by remember{mutableStateOf(false)}
    var rename by remember{mutableStateOf(false)}
    var remove by remember{mutableStateOf(false)}
    var linkDialog by remember{mutableStateOf(false)}
    var picker by remember{mutableStateOf(false)}
    var choosing by remember(initialAdd,initialLink){mutableStateOf(initialAdd.isNotEmpty()||initialLink!=null)}
    var error by remember{mutableStateOf<String?>(null)}
    var busy by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val byId=remember(refs){refs.associateBy{it.key}}
    val resolved by produceState(emptyMap<String,LibraryMediaRef>(),collections,refs,ready){
        value=if(!ready)emptyMap() else withContext(Dispatchers.Default){MediaOrganizationPolicy.resolve(collections.flatMap{it.members},refs)}
    }
    val collection=collections.firstOrNull{it.record.id==selected}
    fun mutate(action:suspend ()->Unit){scope.launch{
        busy=true;error=null
        try{action()}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"Не удалось выполнить действие"}finally{busy=false}
    }}
    fun addInitial(id:String){mutate{
        if(initialAdd.isNotEmpty()){
            val files=initialAdd.distinct().map{byId[it]?:error("Часть файлов недоступна")}
            repository.addMedia(id,files)
        }
        initialLink?.let{repository.addLink(id,it)}
        choosing=false;onAddComplete();selected=id
    }}
    BackHandler(selected!=null&&!choosing){selected=null}
    Column(Modifier.fillMaxSize().testTag("collections-screen")){
        Header(if(choosing)"Добавить в коллекцию" else collection?.record?.name?:"Коллекции",{
            if(selected!=null&&!choosing)selected=null else onBack()
        }){
            if(collection==null||choosing)IconButton({create=true},enabled=!busy&&(!choosing||ready)){Icon(Icons.Rounded.Add,"Создать коллекцию")}
            else {
                IconButton({rename=true},enabled=!busy){Icon(Icons.Rounded.Edit,"Переименовать коллекцию")}
                IconButton({remove=true},enabled=!busy){Icon(Icons.Rounded.DeleteOutline,"Удалить коллекцию")}
            }
        }
        if(!ready||busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let{Text(it,Modifier.padding(12.dp),color=MaterialTheme.colorScheme.error)}
        if(collection==null||choosing){
            LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
                if(collections.isEmpty())item{Text("Создай коллекцию, чтобы собрать разные медиа и ссылки в одном месте. Файлы не копируются.")}
                items(collections,key={it.record.id}){c->
                    val visible=c.members.mapNotNull{resolved[it.id]?.let{ref->byId[ref.key]}}
                    val count=if(audioOnly)visible.count{it.item.mediaType==MediaType.AUDIO} else visible.size
                    OrganizationEntry(c.record.name,if(audioOnly)"Музыка · $count треков" else "${c.members.size} материалов",
                        {if(choosing){if(ready&&!busy)addInitial(c.record.id)}else selected=c.record.id})
                }
            }
        } else {
            val visible=collection.members.mapNotNull{member->resolved[member.id]?.let{byId[it.key]}?.let{member to it}}
            val audio=visible.filter{it.second.item.mediaType==MediaType.AUDIO}.map{it.second.key}.distinct()
            val filtered=visible.filter{!audioOnly||it.second.item.mediaType==MediaType.AUDIO}
                .filter{filter=="Всё"||it.second.item.mediaType.caption()==filter}
            val links=collection.members.filter{it.link!=null}
            val missing=collection.members.count{it.link==null&&it.id !in resolved}
            LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                item{
                    Text(if(audioOnly)"Треков: ${audio.size}" else "Фото: ${visible.count{it.second.item.mediaType==MediaType.PHOTO}} · Видео: ${visible.count{it.second.item.mediaType==MediaType.VIDEO}} · Музыка: ${audio.size} · Ссылки: ${links.size}")
                    if(!audioOnly)Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        listOf("Всё","Фото","Видео","Музыка","Ссылки").forEach{label->FilterChip(filter==label,{filter=label},{Text(label)})}
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        OutlinedButton({picker=true},enabled=ready&&!busy,modifier=Modifier.weight(1f)){Text("Добавить файлы")}
                        if(!audioOnly)OutlinedButton({linkDialog=true},enabled=!busy,modifier=Modifier.weight(1f)){Text("Добавить ссылку")}
                    }
                    if(audio.isNotEmpty())Button({onPlay(audio)},enabled=ready&&!busy,modifier=Modifier.fillMaxWidth()){
                        Icon(Icons.Rounded.PlayArrow,null);Text("Слушать коллекцию")
                    }
                    if(missing>0&&!audioOnly)Text("Недоступно файлов: $missing. Связи сохранены.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(filtered,key={it.first.id}){(member,ref)->
                    val row=ref.item
                    MediaLine(row,{onOpen(ref.key,visible.filter{it.second.item.mediaType==row.mediaType}.map{it.second.key})}){
                        IconButton({onPassport(ref.key)}){Icon(Icons.Rounded.Info,"Паспорт файла")}
                        IconButton({mutate{repository.remove(collection.record.id,member.id)}},enabled=!busy){Icon(Icons.Rounded.Close,"Убрать из коллекции")}
                    }
                }
                if(!audioOnly&&(filter=="Всё"||filter=="Ссылки"))items(links,key={it.id}){member->
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                        Icon(Icons.Rounded.Link,null)
                        Text(member.link.orEmpty(),Modifier.weight(1f).clickable{member.link?.let(onLink)}.padding(8.dp),maxLines=2,overflow=TextOverflow.Ellipsis)
                        IconButton({mutate{repository.remove(collection.record.id,member.id)}},enabled=!busy){Icon(Icons.Rounded.Close,"Убрать ссылку")}
                    }
                }
                if(!audioOnly&&filter=="Всё")items(collection.members.filter{it.link==null&&it.id !in resolved},key={it.id}){member->
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Text("Недоступный файл",Modifier.weight(1f))
                        IconButton({mutate{repository.remove(collection.record.id,member.id)}},enabled=!busy){Icon(Icons.Rounded.Close,"Убрать недоступную запись")}
                    }
                }
            }
        }
    }
    if(create)ValueDialog("Новая коллекция","","Название",{create=false}){name->
        val files=if(choosing)initialAdd.distinct().map{byId[it]?:error("Файл недоступен")} else emptyList()
        val id=repository.create(name,files,if(choosing)initialLink else null)
        if(choosing){choosing=false;onAddComplete()}
        selected=id
    }
    if(rename&&collection!=null)ValueDialog("Переименовать",collection.record.name,"Название",{rename=false}){repository.rename(collection.record.id,it)}
    if(linkDialog&&collection!=null)ValueDialog("Добавить ссылку","","HTTP(S) ссылка",{linkDialog=false}){repository.addLink(collection.record.id,it)}
    if(remove&&collection!=null)AlertDialog(onDismissRequest={remove=false},title={Text("Удалить коллекцию?")},
        text={Text("Будет удалена только подборка «${collection.record.name}». Все файлы, альбомы и плейлисты останутся.")},
        confirmButton={TextButton({remove=false;mutate{repository.delete(collection.record.id);selected=null}}){Text("Удалить",color=MaterialTheme.colorScheme.error)}},
        dismissButton={TextButton({remove=false}){Text("Отмена")}})
    if(picker&&collection!=null)MediaPicker(refs.filter{!audioOnly||it.item.mediaType==MediaType.AUDIO},{picker=false}){ids->
        repository.addMedia(collection.record.id,ids.map{byId[it]?:error("Файл недоступен")});picker=false
    }
}
@Composable
private fun MediaPicker(refs:List<LibraryMediaRef>,onDismiss:()->Unit,onSave:suspend (List<String>)->Unit) {
    var search by remember{mutableStateOf("")}
    var selected by remember{mutableStateOf<Set<String>>(emptySet())}
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    val matching=remember(refs,search){refs.filter{it.item.originalName.contains(search,true)}}
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text("Выбрать файлы")},text={
        Column{
            OutlinedTextField(search,{search=it},label={Text("Поиск")},singleLine=true)
            Text("Выбрано: ${selected.size}")
            LazyColumn(Modifier.heightIn(max=330.dp)){
                items(matching,key={it.key}){ref->
                    Row(Modifier.fillMaxWidth().clickable{selected=if(ref.key in selected)selected-ref.key else selected+ref.key},verticalAlignment=Alignment.CenterVertically){
                        Checkbox(ref.key in selected,{checked->selected=if(checked)selected+ref.key else selected-ref.key})
                        Text(ref.item.originalName,maxLines=2,overflow=TextOverflow.Ellipsis)
                    }
                }
            }
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        }
    },confirmButton={TextButton(enabled=selected.isNotEmpty()&&!busy,onClick={scope.launch{
        busy=true;try{onSave(selected.toList())}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"Не удалось добавить"}finally{busy=false}
    }}){Text("Добавить")}},dismissButton={TextButton(onDismiss,enabled=!busy){Text("Отмена")}})
}

@Composable
internal fun ReviewScreen(entries:List<MediaReviewEntity>,refs:List<LibraryMediaRef>,folders:List<CatalogFolderEntity>,
    favorites:Set<String>,repository:MediaOrganizationRepository,ready:Boolean,onBack:()->Unit,onOpen:(String,List<String>)->Unit,
    onEdit:(DownloadEntity)->Unit,onTrash:(String)->Unit,onAddCollection:(List<String>)->Unit,onPassport:(String)->Unit,
    onFavorite:suspend (String,Boolean)->Unit,onFolder:suspend (String,String?)->Unit,onPlaylist:(String)->Unit) {
    val byDownload=remember(refs){refs.mapNotNull{it.appDownloadId?.let{id->id to it}}.toMap()}
    val visible=remember(entries,byDownload){entries.mapNotNull{entry->byDownload[entry.downloadId]?.let{entry to it}}}
    val scope=rememberCoroutineScope()
    var folder by remember{mutableStateOf<String?>(null)}
    var error by remember{mutableStateOf<String?>(null)}
    var busy by remember{mutableStateOf(false)}
    fun mutate(action:suspend ()->Unit){scope.launch{busy=true;error=null
        try{action()}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"Не удалось выполнить действие"}finally{busy=false}}}
    Column(Modifier.fillMaxSize().testTag("review-screen")){
        Header("На разбор",onBack)
        if(!ready||busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let{Text(it,Modifier.padding(12.dp),color=MaterialTheme.colorScheme.error)}
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            item{Text("Новые загрузки: ${visible.size}. Файлы остаются на своих местах.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
            if(ready&&visible.isEmpty())item{Text("Всё разобрано. Новые успешные загрузки появятся здесь.")}
            visible.groupBy{(entry,ref)->ref.item.groupId?:entry.downloadId}.forEach{(group,rows)->
                item(key="group:$group"){
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                        Column(Modifier.weight(1f)){
                            Text(rows.first().second.item.providerId,style=MaterialTheme.typography.titleMedium)
                            Text("${date(rows.first().first.completedAt)} · ${rows.size} файлов",style=MaterialTheme.typography.bodySmall)
                        }
                        if(rows.size>1)TextButton({mutate{repository.setReviewed(rows.map{it.first.downloadId})}},enabled=!busy){Text("Готово")}
                    }
                }
                items(rows,key={it.first.downloadId}){(entry,ref)->
                    val row=ref.item;val id=ref.key
                    var menu by remember{mutableStateOf(false)}
                    Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant){
                        Column(Modifier.padding(10.dp)){
                            MediaLine(row,{onOpen(id,rows.filter{it.second.item.mediaType==row.mediaType}.map{it.second.key})}){
                                Box{
                                    IconButton({menu=true}){Icon(Icons.Rounded.MoreVert,"Действия")}
                                    DropdownMenu(menu,{menu=false}){
                                        DropdownMenuItem({Text("В коллекцию")},{menu=false;onAddCollection(listOf(id))})
                                        if(row.mediaType==MediaType.AUDIO)DropdownMenuItem({Text("В плейлист")},{menu=false;onPlaylist(id)})
                                        else DropdownMenuItem({Text("В альбом")},{menu=false;folder=id})
                                        DropdownMenuItem({Text("Редактировать")},{menu=false;onEdit(row)})
                                        DropdownMenuItem({Text("Паспорт файла")},{menu=false;onPassport(id)})
                                        DropdownMenuItem({Text("В корзину")},{menu=false;onTrash(id)})
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                                IconButton({mutate{onFavorite(id,id !in favorites)}},enabled=!busy){Icon(if(id in favorites)Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,"Избранное")}
                                Spacer(Modifier.weight(1f))
                                TextButton({mutate{repository.setReviewed(listOf(entry.downloadId))}},enabled=!busy){Text("Готово")}
                            }
                        }
                    }
                }
            }
        }
    }
    folder?.let{id->AlertDialog(onDismissRequest={folder=null},title={Text("Добавить в альбом")},
        text={LazyColumn(Modifier.heightIn(max=300.dp)){items(folders,key={it.id}){f->TextButton({folder=null;mutate{onFolder(id,f.id)}}){Text(f.name)}}}},
        confirmButton={TextButton({folder=null}){Text("Закрыть")}})}
}

@Composable
internal fun MediaPassportScreen(ref:LibraryMediaRef?,refs:List<LibraryMediaRef>,collections:List<MediaCollection>,
    operations:List<MediaOperationEntity>,folderNames:List<String>,playlistNames:List<String>,onBack:()->Unit,
    onOpen:(String,List<String>)->Unit,onLink:(String)->Unit,onCollection:(String)->Unit) {
    Column(Modifier.fillMaxSize().testTag("media-passport")){
        Header("Паспорт медиафайла",onBack)
        if(ref==null){Text("Файл недоступен",Modifier.padding(16.dp));return@Column}
        val row=ref.item
        val passport=remember(ref,refs){MediaOrganizationPolicy.passport(ref,refs)}
        val membership by produceState(emptyList<MediaCollection>(),ref,collections,refs){
            value=withContext(Dispatchers.Default){
                val matches=MediaOrganizationPolicy.resolve(collections.flatMap{it.members},refs)
                collections.filter{collection->collection.members.any{matches[it.id]?.key==ref.key}}
            }
        }
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            item{
                DownloadThumbnail(row.savedUri,row.previewUrl,row.mediaType.kind(),Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(16.dp)))
                Text(row.originalName,style=MaterialTheme.typography.titleLarge)
                Text("${row.mediaType.caption()} · ${row.sizeBytes?.let{android.text.format.Formatter.formatFileSize(androidx.compose.ui.platform.LocalContext.current,it)}?:"Размер неизвестен"}")
                row.durationMs?.let{Text("Длительность: ${it/1000} с")}
                Text("Дата записи: ${date(row.createdAtEpochMillis)}")
                Text(if(ref.appDownloadId!=null&&row.providerId!="device")"Источник: ${row.providerId}" else "Файл телефона; источник не подтверждён")
                row.sourcePageUrl?.let{url->if(runCatching{MediaOrganizationPolicy.link(url)}.isSuccess)TextButton({onLink(url)}){Text("Открыть исходную публикацию")}}
                HorizontalDivider()
                Text("История обработки",style=MaterialTheme.typography.titleMedium)
                if(operations.isEmpty())Text(if(passport.parentKnown)"Создан производный файл; точная операция не записана" else "Нет подтверждённых операций")
                operations.forEach{Text("${date(it.occurredAt)} · ${it.operation}")}
            }
            if(passport.parentKnown)item{
                Text("Оригинал",style=MaterialTheme.typography.titleMedium)
                passport.parent?.let{parent->MediaLine(parent.item,{onOpen(parent.key,listOf(parent.key))})}
                    ?:Text("Исходный файл недоступен")
            }
            if(passport.children.isNotEmpty())item{Text("Связанные версии",style=MaterialTheme.typography.titleMedium)}
            items(passport.children,key={it.key}){child->MediaLine(child.item,{onOpen(child.key,listOf(child.key))})}
            item{
                HorizontalDivider();Text("Альбомы и плейлисты",style=MaterialTheme.typography.titleMedium)
                (folderNames+playlistNames).distinct().forEach{Text(it)}
                Text("Коллекции",style=MaterialTheme.typography.titleMedium)
                if(membership.isEmpty())Text("Не добавлен в коллекции")
                membership.forEach{c->TextButton({onCollection(c.record.id)}){Text(c.record.name)}}
            }
        }
    }
}

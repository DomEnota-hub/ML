package com.domenota.medialoader.ui.screens.organization

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import com.domenota.medialoader.ui.screens.music.MusicLibraryTrackRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
    onAddComplete:()->Unit,usable:Boolean=ready,initialCreate:Boolean=false) {
    var selected by rememberSaveable(initialCollection){mutableStateOf(initialCollection)}
    var filter by rememberSaveable(audioOnly){mutableStateOf(if(audioOnly)"Музыка" else "Всё")}
    var create by rememberSaveable(initialCreate){mutableStateOf(initialCreate)}
    var rename by rememberSaveable{mutableStateOf(false)}
    var remove by remember{mutableStateOf(false)}
    var linkDialog by remember{mutableStateOf(false)}
    var picker by rememberSaveable{mutableStateOf(false)}
    var largeCards by rememberSaveable{mutableStateOf(false)}
    var detailMenu by remember{mutableStateOf(false)}
    var selecting by rememberSaveable(selected){mutableStateOf(false)}
    var chosenMembers by rememberSaveable(selected){mutableStateOf(arrayListOf<String>())}
    var removeMembers by remember{mutableStateOf(false)}
    var choosing by remember(initialAdd,initialLink){mutableStateOf(initialAdd.isNotEmpty()||initialLink!=null)}
    var error by remember{mutableStateOf<String?>(null)}
    var busy by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val byId=remember(refs){refs.associateBy{it.key}}
    var resolved by remember { mutableStateOf(emptyMap<String,LibraryMediaRef>()) }
    LaunchedEffect(collections,refs,ready) {
        resolved = if(refs.isEmpty()) emptyMap() else withContext(Dispatchers.Default) { MediaOrganizationPolicy.resolve(collections.flatMap { it.members },refs) }
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
    val context=androidx.compose.ui.platform.LocalContext.current
    if(picker&&collection!=null){
        val existing=collection.members.mapNotNull{resolved[it.id]?.let{candidate->byId[candidate.key]}}.map(::collectionPickerKey).toSet()
        CollectionMediaPicker(collection.record.name,refs,existing,collections.flatMap{it.members}.mapNotNull{it.link},collection.members.mapNotNull{it.link}.toSet(),audioOnly,{picker=false}){files,links->
            check(ready){"Библиотека обновляется. Повторите добавление после обновления"}
            repository.addSelection(collection.record.id,files,links);picker=false
        }
        return
    }
    if(rename&&collection!=null){
        CollectionCoverEditor(collection,refs,{rename=false}){repository.rename(collection.record.id,it)}
        return
    }
    if(create){
        CollectionCoverEditor(null,refs,{if(initialCreate&&selected==null)onBack() else create=false},
            onCreate={name,source,image,crop->
                val files=if(choosing)initialAdd.distinct().map{byId[it]?:error("Файл недоступен")} else emptyList()
                val id=repository.create(name,files,if(choosing)initialLink else null)
                try {
                    if(source!=null&&image!=null)withContext(Dispatchers.IO){CollectionCoverStore(context).save(id,source,image,crop)}
                }catch(e:Exception){
                    withContext(kotlinx.coroutines.NonCancellable){repository.delete(id);withContext(Dispatchers.IO){CollectionCoverStore(context).automatic(id)}}
                    throw e
                }
                if(choosing){choosing=false;onAddComplete()}
                selected=id;filter=if(audioOnly)"Музыка" else "Всё";create=false
            },onRename={})
        return
    }
    BackHandler(selected!=null&&!choosing){if(selecting){selecting=false;chosenMembers=arrayListOf()}else if(initialCreate||initialCollection!=null)onBack() else selected=null}
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("collections-screen")){
        Header(if(choosing)"Добавить в коллекцию" else if(collection!=null)"Коллекция" else "Коллекции",{
            if(selecting){selecting=false;chosenMembers=arrayListOf()}else if(selected!=null&&!choosing&&!initialCreate&&initialCollection==null)selected=null else onBack()
        }){
            if(collection==null||choosing)IconButton({create=true},enabled=!busy&&(!choosing||ready)){Icon(Icons.Rounded.Add,"Создать коллекцию")}
            else {
                IconButton({rename=true},enabled=!busy){Icon(Icons.Rounded.Edit,"Редактировать коллекцию")}
                Box{
                    IconButton({detailMenu=true},enabled=!busy){Icon(Icons.Rounded.MoreVert,"Меню коллекции")}
                    DropdownMenu(detailMenu,{detailMenu=false}){
                        DropdownMenuItem({Text(if(largeCards)"Компактная сетка" else "Крупные карточки")},{largeCards=!largeCards;detailMenu=false})
                        DropdownMenuItem({Text("Выбрать материалы")},{selecting=true;chosenMembers=arrayListOf();detailMenu=false})
                        DropdownMenuItem({Text("Удалить коллекцию")},{remove=true;detailMenu=false})
                    }
                }
            }
        }
        if(!usable||busy)LinearProgressIndicator(Modifier.fillMaxWidth().testTag("organization-loading"))
        error?.let{Text(it,Modifier.padding(12.dp),color=MaterialTheme.colorScheme.error)}
        if(collection==null||choosing){
            LazyVerticalGrid(GridCells.Fixed(2),Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                if(collections.isEmpty())item(span={GridItemSpan(2)}){Text("Создай коллекцию, чтобы собрать разные медиа и ссылки в одном месте. Файлы не копируются.")}
                items(collections,key={it.record.id}){c->
                    val cover=c.members.firstNotNullOfOrNull{resolved[it.id]?.let{candidate->byId[candidate.key]}}
                    CollectionCard(c,cover,{if(choosing){if(ready&&!busy)addInitial(c.record.id)}else selected=c.record.id},Modifier.fillMaxWidth())
                }
            }
        } else {
            val visible=collection.members.mapNotNull{member->resolved[member.id]?.let{byId[it.key]}?.let{member to it}}
            val audio=visible.filter{it.second.item.mediaType==MediaType.AUDIO}.map{it.second.key}.distinct()
            val filtered=visible.filter{!audioOnly||it.second.item.mediaType==MediaType.AUDIO}
                .filter{filter=="Всё"||it.second.item.mediaType.caption()==filter}
            val links=collection.members.filter{it.link!=null}
            val missing=collection.members.count{it.link==null&&it.id !in resolved}
            val columns=if(largeCards)2 else 3
            val cover=visible.firstOrNull()?.second
            val visual=filtered.filter{it.second.item.mediaType!=MediaType.AUDIO}
            val tracks=filtered.filter{it.second.item.mediaType==MediaType.AUDIO}
            val visibleLinks=if(!audioOnly&&(filter=="Всё"||filter=="Ссылки"))links else emptyList()
            val unavailable=if(!audioOnly&&filter=="Всё"&&ready)collection.members.filter{it.link==null&&it.id !in resolved} else emptyList()
            fun toggleMember(id:String){chosenMembers=ArrayList(if(id in chosenMembers)chosenMembers-id else chosenMembers+id)}
            fun openMember(ref:LibraryMediaRef){onOpen(ref.key,visible.filter{it.second.item.mediaType==ref.item.mediaType}.map{it.second.key})}
            fun removeMember(id:String){mutate{repository.remove(collection.record.id,id)}}
            LazyVerticalGrid(GridCells.Fixed(columns),Modifier.weight(1f).testTag("collection-detail-grid"),
                contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp),
                horizontalArrangement=Arrangement.spacedBy(4.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                item(span={GridItemSpan(maxLineSpan)}){
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                            CollectionCoverImage(collection,cover,Modifier.width(78.dp).aspectRatio(.8f).clip(RoundedCornerShape(10.dp)).clickable{rename=true})
                            Column(Modifier.weight(1f)){
                                Text(collection.record.name,style=MaterialTheme.typography.titleLarge)
                                Text(collectionMaterialCount(collection.members.size),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            Button({picker=true},enabled=ready&&!busy,modifier=Modifier.weight(1f)){Icon(Icons.Rounded.Add,null);Text("Добавить")}
                            if(audio.isNotEmpty())OutlinedButton({onPlay(audio)},enabled=ready&&!busy,modifier=Modifier.weight(1f)){
                                Icon(Icons.Rounded.PlayArrow,null);Text("Слушать")
                            }
                        }
                        if(!audioOnly)TextButton({linkDialog=true},enabled=!busy){Text("Добавить ссылку")}
                        if(!audioOnly)Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                            val available=listOf("Всё")+MediaType.entries.filter{type->visible.any{it.second.item.mediaType==type}}.map{it.caption()}+if(links.isNotEmpty())listOf("Ссылки") else emptyList()
                            LaunchedEffect(available){if(filter !in available)filter="Всё"}
                            available.forEach{label->FilterChip(filter==label,{filter=label},{Text(label)})}
                        }
                        if(missing>0&&!audioOnly&&ready)Text("Недоступно файлов: $missing. Связи сохранены.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(collection.members.isEmpty())Text("Пока нет материалов",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(visual,key={it.first.id}){(member,ref)->
                    val row=ref.item
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp)).clickable{
                        if(selecting)toggleMember(member.id) else openMember(ref)
                    }.testTag("collection-media-"+member.id)){
                        DownloadThumbnail(row.savedUri,row.previewUrl,row.mediaType.kind(),Modifier.fillMaxSize())
                        if(row.mediaType==MediaType.VIDEO)Row(Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha=.6f)).padding(4.dp),verticalAlignment=Alignment.CenterVertically){
                            Icon(Icons.Rounded.PlayArrow,null,Modifier.size(16.dp),tint=Color.White)
                            row.durationMs?.let{Text("${it/60000}:${"%02d".format(it/1000%60)}",color=Color.White,style=MaterialTheme.typography.labelSmall)}
                        }
                        Box(Modifier.align(Alignment.TopEnd)){
                            if(selecting)PickerMark(member.id in chosenMembers,false,enabled=!busy){toggleMember(member.id)}
                            else CollectionMemberMenu({onPassport(ref.key)},{removeMember(member.id)},!busy)
                        }
                    }
                }
                items(tracks,key={it.first.id},span={GridItemSpan(maxLineSpan)}){(member,ref)->
                    MusicLibraryTrackRow(ref.item,onPlay={if(selecting)toggleMember(member.id) else onPlay(listOf(ref.key))},isCurrent=false,isPlaying=false){
                        if(selecting)PickerMark(member.id in chosenMembers,false,enabled=!busy){toggleMember(member.id)}
                        else CollectionMemberMenu({onPassport(ref.key)},{removeMember(member.id)},!busy)
                    }
                }
                items(visibleLinks,key={it.id},span={GridItemSpan(maxLineSpan)}){member->
                    val url=member.link.orEmpty()
                    val metadata=refs.firstOrNull{MediaOrganizationPolicy.keys(it).isNotEmpty()&&(it.item.sourcePageUrl==url||it.item.sourceUrl==url)}?.item
                    Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceVariant){
                        Row(Modifier.fillMaxWidth().clickable{if(selecting)toggleMember(member.id) else onLink(url)}.padding(8.dp),verticalAlignment=Alignment.CenterVertically){
                            if(metadata!=null)DownloadThumbnail(metadata.savedUri,metadata.previewUrl,metadata.mediaType.kind(),Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))) else Icon(Icons.Rounded.Link,null,Modifier.size(48.dp))
                            Column(Modifier.weight(1f).padding(horizontal=8.dp)){
                                Text(metadata?.originalName?:url,maxLines=2,overflow=TextOverflow.Ellipsis)
                                Text(runCatching{java.net.URI(url).host}.getOrNull().orEmpty(),style=MaterialTheme.typography.labelSmall)
                            }
                            if(selecting)PickerMark(member.id in chosenMembers,false,enabled=!busy){toggleMember(member.id)}
                            else CollectionMemberMenu(null,{removeMember(member.id)},!busy)
                        }
                    }
                }
                items(unavailable,key={it.id},span={GridItemSpan(maxLineSpan)}){member->
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Text("Недоступный файл",Modifier.weight(1f))
                        if(selecting)PickerMark(member.id in chosenMembers,false,enabled=!busy){toggleMember(member.id)}
                        else CollectionMemberMenu(null,{removeMember(member.id)},!busy)
                    }
                }
            }
            if(selecting)Surface(color=MaterialTheme.colorScheme.surface){
                Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically){
                    Text("Выбрано: ${chosenMembers.size}",Modifier.weight(1f))
                    TextButton({selecting=false;chosenMembers=arrayListOf()}){Text("Отмена")}
                    Button({removeMembers=true},enabled=!busy&&chosenMembers.isNotEmpty()){Text("Убрать")}
                }
            }

        }
    }
    if(removeMembers&&collection!=null)AlertDialog(onDismissRequest={removeMembers=false},title={Text("Убрать материалы?")},
        text={Text("Будут удалены только связи с коллекцией. Файлы останутся на месте.")},
        confirmButton={TextButton({removeMembers=false;mutate{repository.removeSelection(collection.record.id,chosenMembers);chosenMembers=arrayListOf();selecting=false}}){Text("Убрать")}},
        dismissButton={TextButton({removeMembers=false}){Text("Отмена")}})
    if(linkDialog&&collection!=null)ValueDialog("Добавить ссылку","","HTTP(S) ссылка",{linkDialog=false}){repository.addLink(collection.record.id,it)}
    if(remove&&collection!=null)AlertDialog(onDismissRequest={remove=false},title={Text("Удалить коллекцию?")},
        text={Text("Будет удалена только подборка «${collection.record.name}». Все файлы, альбомы и плейлисты останутся.")},
        confirmButton={TextButton({remove=false;mutate{repository.delete(collection.record.id);withContext(Dispatchers.IO){CollectionCoverStore(context).automatic(collection.record.id)};selected=null}}){Text("Удалить",color=MaterialTheme.colorScheme.error)}},
        dismissButton={TextButton({remove=false}){Text("Отмена")}})
}

internal fun collectionMaterialCount(count:Int):String {
    val ending=when{
        count%100 in 11..14->"материалов"
        count%10==1->"материал"
        count%10 in 2..4->"материала"
        else->"материалов"
    }
    return "$count $ending"
}

@Composable
private fun CollectionMemberMenu(onInfo:(()->Unit)?,onRemove:()->Unit,enabled:Boolean){
    var expanded by remember{mutableStateOf(false)}
    Box{
        IconButton({expanded=true},enabled=enabled){Icon(Icons.Rounded.MoreVert,"Действия материала")}
        DropdownMenu(expanded,{expanded=false}){
            if(onInfo!=null)DropdownMenuItem({Text("Паспорт файла")},{expanded=false;onInfo()})
            DropdownMenuItem({Text("Убрать из коллекции")},{expanded=false;onRemove()})
        }
    }
}

@Composable
internal fun ReviewScreen(entries:List<MediaReviewEntity>,refs:List<LibraryMediaRef>,folders:List<CatalogFolderEntity>,
    favorites:Set<String>,repository:MediaOrganizationRepository,ready:Boolean,onBack:()->Unit,onOpen:(String,List<String>)->Unit,
    onEdit:(DownloadEntity)->Unit,onTrash:(String)->Unit,onAddCollection:(List<String>)->Unit,onPassport:(String)->Unit,
    onFavorite:suspend (String,Boolean)->Unit,onFolder:suspend (String,String?)->Unit,onPlaylist:(String)->Unit,usable:Boolean=ready) {
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
        if(!usable||busy)LinearProgressIndicator(Modifier.fillMaxWidth().testTag("organization-loading"))
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
        var membership by remember(ref,collections,refs) { mutableStateOf(emptyList<MediaCollection>()) }
        LaunchedEffect(ref,collections,refs) {
            membership = withContext(Dispatchers.Default) {
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

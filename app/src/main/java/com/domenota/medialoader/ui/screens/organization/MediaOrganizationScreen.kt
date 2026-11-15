package com.domenota.medialoader.ui.screens.organization

import com.domenota.medialoader.ui.model.displayFileName
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import com.domenota.medialoader.core.model.RussianCounts
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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.text.font.FontWeight
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
private fun Header(title:String,onBack:()->Unit,compact:Boolean=false,actions:@Composable RowScope.()->Unit={}) {
    Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
        if(compact)Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surfaceVariant){
            IconButton(onBack){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад",Modifier.size(22.dp))}
        }else IconButton(onBack){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")}
        Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
        actions()
    }
}
@Composable
private fun MediaLine(row:DownloadEntity,onOpen:()->Unit,actions:@Composable RowScope.()->Unit={}) {
    Row(Modifier.fillMaxWidth().padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
        DownloadThumbnail(row.savedUri,row.previewUrl,row.mediaType.kind(),Modifier.size(54.dp).clip(RoundedCornerShape(9.dp)))
        Column(Modifier.weight(1f).clickable(onClick=onOpen)){
            Text(displayFileName(row),maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyLarge)
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
    onAddComplete:()->Unit,usable:Boolean=ready,initialCreate:Boolean=false,
    onPlayTrack:(String,List<String>)->Unit=onOpen,currentPlayingId:String?=null,playing:Boolean=false,
    miniPlayer:@Composable ()->Unit={}, onNoticeFooterHeight:(Int)->Unit={},
    blockedIdentities:Set<String> = emptySet(),history:Map<String,DownloadEntity> = emptyMap(),
    validateSelection:suspend (List<LibraryMediaRef>)->List<LibraryMediaRef> = {it},
    canRecover:(DownloadEntity)->Boolean={false},onRecover:(DownloadEntity)->Unit={},
    identityIndex:CollectionIdentityIndex?=null,externalHistory:List<LibraryMediaRef> = emptyList(),
    scopedDetails:Boolean=false,detailCatalogId:String?=null,detailCatalog:AddressedCollection?=null,
    onSelectedCollection:(String?)->Unit={}, catalogByKey:Map<String,LibraryMediaRef>?=null) {
    var selected by rememberSaveable(initialCollection){mutableStateOf(initialCollection)}
    var filter by rememberSaveable(selected){mutableStateOf("Всё")}
    var musicExpanded by rememberSaveable(selected){mutableStateOf(audioOnly)}
    var ordering by rememberSaveable(selected){mutableStateOf(false)}
    var create by rememberSaveable(initialCreate){mutableStateOf(initialCreate)}
    var rename by rememberSaveable{mutableStateOf(false)}
    var remove by remember{mutableStateOf(false)}
    var linkDialog by remember{mutableStateOf(false)}
    var picker by rememberSaveable{mutableStateOf(false)}
    var largeCards by rememberSaveable{mutableStateOf(false)}
    var detailMenu by remember{mutableStateOf(false)}
    var missingPanel by rememberSaveable(selected){mutableStateOf(false)}
    var missingInfo by remember(selected){mutableStateOf<String?>(null)}
    var missingRemove by remember(selected){mutableStateOf<String?>(null)}
    var selecting by rememberSaveable(selected){mutableStateOf(false)}
    var chosenMembers by rememberSaveable(selected){mutableStateOf(arrayListOf<String>())}
    var removeMembers by remember{mutableStateOf(false)}
    var choosing by remember(initialAdd,initialLink){mutableStateOf(initialAdd.isNotEmpty()||initialLink!=null)}
    var error by remember{mutableStateOf<String?>(null)}
    var busy by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val collection=collections.firstOrNull{it.record.id==selected}
    LaunchedEffect(selected) { onSelectedCollection(selected) }
    val useScoped = scopedDetails && collection != null && !choosing
    val detailScope = detailCatalog.takeIf { useScoped && detailCatalogId == selected }
    val activeUsable = if (useScoped) detailScope != null else usable
    val activeRefs = if (useScoped) detailScope?.refs.orEmpty() else refs
    val activeMembers = remember(useScoped, collection, collections) {
        if (useScoped) collection?.members.orEmpty() else collections.flatMap { it.members }
    }
    val byId=remember(activeRefs,activeUsable,blockedIdentities,useScoped,catalogByKey,detailScope){
        if(!activeUsable) emptyMap() else if (useScoped) detailScope?.byKey.orEmpty()
        else catalogByKey ?: activeRefs.filterNot{collectionRefBlocked(it,blockedIdentities)}.associateBy{it.key}
    }
    // A selected collection resolves only its addressed generation, never all
    // members against a partial phone scan. The full catalog is for the picker.
    val resolved=remember(activeMembers,byId,activeUsable,identityIndex,useScoped,detailScope){
        if(!activeUsable)emptyMap() else if (useScoped) {
            activeMembers.mapNotNull { member -> detailScope?.resolved?.get(member.id)
                ?.takeIf { detailScope?.checkedMembers?.get(member.id) == member.identityJson }?.let { member.id to it } }.toMap()
        }
        else identityIndex?.resolve(activeMembers,byId) ?: MediaOrganizationPolicy.resolve(activeMembers,byId.values.toList())
    }
    fun memberPublic(member:MediaCollectionMemberEntity) = !collectionMemberBlocked(member,blockedIdentities) &&
        detailScope?.suppressed?.contains(member.id) != true
    fun memberChecked(member:MediaCollectionMemberEntity) = !useScoped ||
        (detailScope != null && detailScope.checkedMembers.containsKey(member.id) && detailScope.checkedMembers[member.id] == member.identityJson && member.id !in detailScope.pending)
    val selectableMemberIds=remember(collection?.members,blockedIdentities,detailScope?.suppressed){
        collection?.members.orEmpty().filter{memberPublic(it)}.mapTo(mutableSetOf()){it.id}
    }
    val selectedPublicMembers=chosenMembers.filter{it in selectableMemberIds}
    LaunchedEffect(selectableMemberIds){
        if(chosenMembers.any{it !in selectableMemberIds})chosenMembers=ArrayList(chosenMembers.filter{it in selectableMemberIds})
    }
    LaunchedEffect(collection?.record?.id,choosing,picker,rename,create) {
        if(collection==null||choosing||picker||rename||create) onNoticeFooterHeight(0)
    }
    fun mutate(action:suspend ()->Unit){scope.launch{
        busy=true;error=null
        try{action()}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"Не удалось выполнить действие"}finally{busy=false}
    }}
    fun addInitial(id:String){mutate{
        if(initialAdd.isNotEmpty()){
            val files=initialAdd.distinct().map{byId[it]?:error("Часть файлов недоступна")}
            repository.addMedia(id,validateSelection(files))
        }
        initialLink?.let{repository.addLink(id,it)}
        choosing=false;onAddComplete();selected=id
    }}
    val context=androidx.compose.ui.platform.LocalContext.current
    if(picker&&collection!=null&&activeUsable){
        if (!usable) {
            BackHandler { picker=false }
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Header("Добавить в коллекцию", { picker=false }) {}
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Загрузка списка файлов…", Modifier.padding(16.dp))
            }
            return
        }
        val existing=collection.members.mapNotNull{resolved[it.id]}.map(::collectionPickerKey).toSet()
        CollectionMediaPicker(collection.record.name,refs,existing,collections.flatMap{it.members}.mapNotNull{it.link},collection.members.mapNotNull{it.link}.toSet(),audioOnly,{picker=false}){files,links->
            check(usable){"Библиотека недоступна"}
            repository.addSelection(collection.record.id,validateSelection(files),links);picker=false
        }
        return
    }
    LaunchedEffect(activeUsable){if(!activeUsable)picker=false}
    if(rename&&collection!=null){
        CollectionCoverEditor(collection,refs,{rename=false},blockedIdentities=blockedIdentities.takeIf{activeUsable}){repository.rename(collection.record.id,it)}
        return
    }
    if(create){
        CollectionCoverEditor(null,refs,{if(initialCreate&&selected==null)onBack() else create=false},
            onCreate={name,source,image,crop->
                val files=if(choosing)initialAdd.distinct().map{byId[it]?:error("Файл недоступен")} else emptyList()
                val id=repository.create(name,validateSelection(files),if(choosing)initialLink else null)
                try {
                    if(source!=null&&image!=null)withContext(Dispatchers.IO){CollectionCoverStore(context).save(id,source,image,crop)}
                }catch(e:Exception){
                    withContext(kotlinx.coroutines.NonCancellable){repository.delete(id);withContext(Dispatchers.IO){CollectionCoverStore(context).automatic(id)}}
                    throw e
                }
                if(choosing){choosing=false;onAddComplete()}
                selected=id;filter="Всё";create=false
            },blockedIdentities=blockedIdentities.takeIf{activeUsable},onRename={})
        return
    }
    BackHandler(selected!=null&&!choosing){if(ordering){ordering=false}else if(selecting){selecting=false;chosenMembers=arrayListOf()}else if(initialCreate||initialCollection!=null)onBack() else selected=null}
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("collections-screen")){
        Header(if(choosing)"Добавить в коллекцию" else if(collection!=null)"Коллекция" else "Коллекции",{
            if(ordering){ordering=false}else if(selecting){selecting=false;chosenMembers=arrayListOf()}else if(selected!=null&&!choosing&&!initialCreate&&initialCollection==null)selected=null else onBack()
        },compact=collection!=null&&!choosing){
            if(collection==null||choosing)IconButton({create=true},enabled=!busy&&(!choosing||ready)){Icon(Icons.Rounded.Add,"Создать коллекцию")}

        }
        if(useScoped) Box(Modifier.fillMaxWidth().height(4.dp)) {
            if(!activeUsable||busy||detailScope?.pending?.isNotEmpty()==true)
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("organization-loading"))
        } else if(!activeUsable||busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("organization-loading"))
        error?.let{Text(it,Modifier.padding(12.dp),color=MaterialTheme.colorScheme.error)}
        if(collection==null||choosing){
            LazyVerticalGrid(GridCells.Fixed(2),Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                if(collections.isEmpty())item(span={GridItemSpan(2)}){Text("Создай коллекцию, чтобы собрать разные медиа и ссылки в одном месте. Файлы не копируются.")}
                items(collections,key={it.record.id}){c->
                    val cover=c.members.firstNotNullOfOrNull{resolved[it.id]}
                    CollectionCard(c,cover,{if(choosing){if(ready&&!busy)addInitial(c.record.id)}else selected=c.record.id},Modifier.fillMaxWidth(),blockedIdentities.takeIf{activeUsable})
                }
            }
        } else {
            val publicMembers=remember(collection.members,blockedIdentities,detailScope?.suppressed){collection.members.filter{memberPublic(it)}}
            val visible=publicMembers.mapNotNull{member->resolved[member.id]?.let{member to it}}
            val audio=visible.filter{it.second.item.mediaType==MediaType.AUDIO}.map{it.second.key}.distinct()
            val allVisual=visible.filter{it.second.item.mediaType!=MediaType.AUDIO}
            val availableFilters=listOf("Всё")+listOf(MediaType.PHOTO,MediaType.VIDEO).filter{type->allVisual.any{it.second.item.mediaType==type}}.map{it.caption()}
            LaunchedEffect(availableFilters,activeUsable){if(activeUsable&&filter !in availableFilters)filter="Всё"}
            val filtered=allVisual.filter{!audioOnly&&(filter=="Всё"||it.second.item.mediaType.caption()==filter)}
            val links=collection.members.filter{it.link!=null}
            val unavailable=if(activeUsable)publicMembers.filter{it.link==null&&it.id !in resolved&&memberChecked(it)} else emptyList()
            val missing=unavailable.size
            val missingMetadata=remember(unavailable,history,externalHistory,blockedIdentities,detailScope){
                fun canRead(type: MediaType) = PhoneMediaLibrary.canReadMissingMetadata(type, android.os.Build.VERSION.SDK_INT) {
                    androidx.core.content.ContextCompat.checkSelfPermission(context,it)==android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                val external=MediaOrganizationPolicy.resolve(unavailable,externalHistory.filterNot{collectionRefBlocked(it,blockedIdentities)})
                unavailable.mapNotNull{ member ->
                    (collectionMissingHistory(member,history,blockedIdentities)
                        ?: external[member.id]?.item?.takeIf { canRead(it.mediaType) }
                        ?: CollectionMemberMetadata.display(member,blockedIdentities,::canRead))?.let{member.id to it}
                }.toMap()
            }
            LaunchedEffect(missing,activeUsable){if(missing==0||!activeUsable){missingPanel=false;missingInfo=null;missingRemove=null}}
            val columns=if(largeCards)2 else 3
            val cover=visible.firstOrNull()?.second
            val tracks=visible.filter{it.second.item.mediaType==MediaType.AUDIO}
            val visibleLinks=if(!audioOnly)links else emptyList()
            val visualIds=filtered.map{it.first.id}
            val grid=rememberLazyGridState()
            var draft by remember(collection.record.id,visualIds){mutableStateOf<List<String>?>(null)}
            var dragged by remember(collection.record.id,visualIds){mutableStateOf<String?>(null)}
            var pointer by remember{mutableStateOf(Offset.Zero)}
            var grab by remember{mutableStateOf(Offset.Zero)}
            val edge=with(LocalDensity.current){48.dp.toPx()}
            val canOrder=ordering&&activeUsable&&!busy&&visualIds.size>1
            val orderedRows=(draft?:visualIds).mapNotNull{id->filtered.firstOrNull{it.first.id==id}}
            fun hit(point:Offset)=grid.layoutInfo.visibleItemsInfo.firstOrNull{
                it.key.toString() in visualIds && point.x>=it.offset.x && point.x<it.offset.x+it.size.width &&
                    point.y>=it.offset.y && point.y<it.offset.y+it.size.height
            }
            fun moveAt(point:Offset){
                val id=dragged?:return
                val target=hit(point)?.key as? String?:return
                val order=draft?:visualIds
                draft=moveCollectionMember(order,id,target)
            }
            fun saveOrder(order:List<String>){
                if(order==visualIds){draft=null;return}
                mutate{try{repository.reorder(collection.record.id,visualIds,order)}finally{draft=null}}
            }
            LaunchedEffect(dragged,canOrder){
                while(dragged!=null&&canOrder){
                    val height=grid.layoutInfo.viewportSize.height.toFloat()
                    val delta=when{
                        pointer.y<edge -> -((edge-pointer.y)/edge).coerceIn(0f,1f)*18f
                        pointer.y>height-edge -> ((pointer.y-height+edge)/edge).coerceIn(0f,1f)*18f
                        else -> 0f
                    }
                    if(delta!=0f){grid.scrollBy(delta);moveAt(pointer)}
                    delay(16)
                }
            }
            fun toggleMember(id:String){chosenMembers=ArrayList(if(id in chosenMembers)chosenMembers-id else chosenMembers+id)}
            fun openMember(ref:LibraryMediaRef){onOpen(ref.key,visible.filter{it.second.item.mediaType==ref.item.mediaType}.map{it.second.key})}
            fun removeMember(id:String){mutate{repository.remove(collection.record.id,id)}}
            LazyVerticalGrid(GridCells.Fixed(columns),state=grid,modifier=Modifier.weight(1f).testTag("collection-detail-grid")
                .pointerInput(canOrder,visualIds){
                    if(canOrder)detectDragGesturesAfterLongPress(
                        onDragStart={point->
                            hit(point)?.let{item->
                                dragged=item.key as String;pointer=point
                                grab=point-Offset(item.offset.x.toFloat(),item.offset.y.toFloat())
                                draft=visualIds
                            }
                        },
                        onDragCancel={dragged=null;draft=null},
                        onDragEnd={val order=draft;dragged=null;if(order!=null)saveOrder(order)}
                    ){change,delta->if(dragged!=null){change.consume();pointer+=delta;moveAt(pointer)}}
                },
                contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp),
                horizontalArrangement=Arrangement.spacedBy(4.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                item(key="collection-header",span={GridItemSpan(maxLineSpan)}){
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Row(verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                            CollectionCoverImage(collection,cover,Modifier.width(78.dp).aspectRatio(.8f).clip(RoundedCornerShape(14.dp)).clickable{rename=true},blockedIdentities.takeIf{activeUsable})
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
                                Row(verticalAlignment=Alignment.CenterVertically){
                                    Text(collection.record.name,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,
                                        fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                                    IconButton({rename=true},enabled=!busy){Icon(Icons.Rounded.Edit,"Редактировать коллекцию",Modifier.size(20.dp))}
                                    Box{
                                        IconButton({detailMenu=true},enabled=!busy){Icon(Icons.Rounded.MoreVert,"Меню коллекции")}
                                        DropdownMenu(detailMenu,{detailMenu=false}){
                                            DropdownMenuItem({Text(if(largeCards)"Компактная сетка" else "Крупные карточки")},{largeCards=!largeCards;detailMenu=false})
                                            DropdownMenuItem({Text("Выбрать файлы и ссылки")},{ordering=false;selecting=true;chosenMembers=arrayListOf();detailMenu=false})
                                            if(!audioOnly)DropdownMenuItem({Text(if(ordering)"Завершить сортировку" else "Изменить порядок")},{
                                                ordering=!ordering;selecting=false;chosenMembers=arrayListOf();detailMenu=false
                                            },enabled=activeUsable&&!busy)
                                            DropdownMenuItem({Text("Удалить коллекцию")},{remove=true;detailMenu=false})
                                        }
                                    }
                                }
                                val files=publicMembers.count{it.link==null}
                                if(files>0||links.isEmpty())Text(RussianCounts.files(files),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                if(links.isNotEmpty())Text(RussianCounts.links(links.size),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                if(missing>0) Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.primaryContainer,
                                    modifier=Modifier.heightIn(min=48.dp).clickable{missingPanel=true}.testTag("collection-missing-chip")){
                                    Row(Modifier.padding(horizontal=10.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
                                        Icon(Icons.Rounded.ErrorOutline,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.primary)
                                        Text(if(missing%10==1&&missing%100!=11)"$missing недоступен" else "$missing недоступны",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            Button({picker=true},enabled=activeUsable&&!busy,modifier=Modifier.weight(1f)){Icon(Icons.Rounded.Add,null);Text("Добавить")}
                            if(audio.isNotEmpty())OutlinedButton({onPlay(audio)},enabled=activeUsable&&!busy,modifier=Modifier.weight(1f)){
                                Icon(Icons.Rounded.PlayArrow,null);Text("Слушать")
                            }
                        }
                        if(!audioOnly)TextButton({linkDialog=true},enabled=!busy){Icon(Icons.Rounded.Link,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("Добавить ссылку")}

                        if(collection.members.isEmpty())Text("Пока нет файлов и ссылок",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if(tracks.isNotEmpty()){
                    item(key="music-header",span={GridItemSpan(maxLineSpan)}){
                        Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceVariant,
                            modifier=Modifier.fillMaxWidth().testTag("collection-music-toggle").clickable{musicExpanded=!musicExpanded}){
                            Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                                CollectionMusicGlyph()
                                Column(Modifier.weight(1f)){
                                    Text("Музыка",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.onSurface)
                                    Text(RussianCounts.files(tracks.size),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(if(musicExpanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    if(musicExpanded)"Свернуть музыку" else "Развернуть музыку")
                            }
                        }
                    }
                    if(musicExpanded)items(tracks,key={it.first.id},span={GridItemSpan(maxLineSpan)}){(member,ref)->
                        MusicLibraryTrackRow(ref.item,onPlay={if(selecting)toggleMember(member.id) else onPlayTrack(ref.key,audio)},
                            isCurrent=currentPlayingId==ref.key,isPlaying=playing&&currentPlayingId==ref.key){
                            if(selecting)PickerMark(member.id in chosenMembers,false,enabled=!busy){toggleMember(member.id)}
                            else CollectionMemberMenu({onPassport(ref.key)},{removeMember(member.id)},!busy)
                        }
                    }
                }
                if(!audioOnly&&allVisual.isNotEmpty())item(key="visual-header",span={GridItemSpan(maxLineSpan)}){
                    Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                            Text("Фото и видео",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                            IconButton({ordering=!ordering;selecting=false;chosenMembers=arrayListOf()},enabled=activeUsable&&!busy&&allVisual.size>1){
                                Icon(if(ordering)Icons.Rounded.Check else Icons.Rounded.SwapVert,if(ordering)"Завершить сортировку" else "Изменить порядок")
                            }
                            IconButton({largeCards=!largeCards},enabled=dragged==null){Icon(Icons.Rounded.GridView,"Изменить размер сетки")}
                        }
                        Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceVariant){
                            Row(Modifier.padding(4.dp),horizontalArrangement=Arrangement.spacedBy(3.dp)){
                                availableFilters.forEach{label->
                                    val active=filter==label
                                    Surface(modifier=Modifier.weight(1f).heightIn(min=48.dp).clickable(enabled=dragged==null&&!busy){filter=label},
                                        shape=RoundedCornerShape(9.dp),color=if(active)MaterialTheme.colorScheme.primaryContainer else Color.Transparent){
                                        Row(Modifier.padding(horizontal=4.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center){
                                            Icon(when(label){"Фото"->Icons.Rounded.Image;"Видео"->Icons.Rounded.Videocam;else->Icons.Rounded.GridView},null,Modifier.size(18.dp),
                                                tint=if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(Modifier.width(4.dp))
                                            Text(label,style=MaterialTheme.typography.labelMedium,color=if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                        if(ordering)Text("Удержание и перетаскивание. Порядок сохраняется в коллекции.",style=MaterialTheme.typography.bodySmall)
                    }
                }
                items(orderedRows,key={it.first.id}){(member,ref)->
                    val row=ref.item
                    val dragging=dragged==member.id
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).zIndex(if(dragging)2f else 0f)
                        .graphicsLayer{
                            if(dragging){
                                grid.layoutInfo.visibleItemsInfo.firstOrNull{it.key==member.id}?.let{
                                    translationX=pointer.x-grab.x-it.offset.x
                                    translationY=pointer.y-grab.y-it.offset.y
                                }
                                scaleX=1.04f;scaleY=1.04f;alpha=.92f
                            }else{translationX=0f;translationY=0f;scaleX=1f;scaleY=1f;alpha=1f}
                        }.clip(RoundedCornerShape(14.dp)).clickable(enabled=!ordering){
                            if(selecting)toggleMember(member.id) else openMember(ref)
                        }.semantics{
                            if(canOrder){
                                val i=visualIds.indexOf(member.id)
                                customActions=buildList{
                                    if(i>0)add(CustomAccessibilityAction("Переместить назад"){saveOrder(moveCollectionMember(visualIds,member.id,visualIds[i-1]));true})
                                    if(i<visualIds.lastIndex)add(CustomAccessibilityAction("Переместить вперёд"){saveOrder(moveCollectionMember(visualIds,member.id,visualIds[i+1]));true})
                                }
                            }
                        }.testTag("collection-media-"+member.id)){
                        DownloadThumbnail(row.savedUri,row.previewUrl,row.mediaType.kind(),Modifier.fillMaxSize())
                        if(row.mediaType==MediaType.VIDEO)Row(Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha=.6f)).padding(4.dp),verticalAlignment=Alignment.CenterVertically){
                            Icon(Icons.Rounded.PlayArrow,null,Modifier.size(16.dp),tint=Color.White)
                            row.durationMs?.let{Text("${it/60000}:${"%02d".format(it/1000%60)}",color=Color.White,style=MaterialTheme.typography.labelSmall)}
                        }
                        Box(Modifier.align(Alignment.TopEnd)){
                            if(ordering)Icon(Icons.Rounded.DragHandle,"Перетащить",Modifier.background(Color.Black.copy(alpha=.6f)).padding(6.dp),tint=Color.White)
                            else if(selecting)PickerMark(member.id in chosenMembers,false,enabled=!busy){toggleMember(member.id)}
                            else CollectionMemberMenu({onPassport(ref.key)},{removeMember(member.id)},!busy)
                        }
                    }
                }
                items(visibleLinks,key={it.id},span={GridItemSpan(maxLineSpan)}){member->
                    val url=member.link.orEmpty()
                    val metadata=refs.firstOrNull{MediaOrganizationPolicy.keys(it).isNotEmpty()&&(it.item.sourcePageUrl==url||it.item.sourceUrl==url)}?.item
                    Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceVariant){
                        Row(Modifier.fillMaxWidth().clickable{if(selecting)toggleMember(member.id) else onLink(url)}.padding(8.dp),verticalAlignment=Alignment.CenterVertically){
                            if(metadata!=null)DownloadThumbnail(metadata.savedUri,metadata.previewUrl,metadata.mediaType.kind(),Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))) else Icon(Icons.Rounded.Link,null,Modifier.size(48.dp))
                            Column(Modifier.weight(1f).padding(horizontal=8.dp)){
                                Text(if(metadata!=null)displayFileName(metadata) else url,maxLines=2,overflow=TextOverflow.Ellipsis)
                                Text(runCatching{java.net.URI(url).host}.getOrNull().orEmpty(),style=MaterialTheme.typography.labelSmall)
                            }
                            if(selecting)PickerMark(member.id in chosenMembers,false,enabled=!busy){toggleMember(member.id)}
                            else CollectionMemberMenu(null,{removeMember(member.id)},!busy)
                        }
                    }
                }
            }
            if(missingPanel && activeUsable && unavailable.isNotEmpty()) MissingCollectionSheet(
                unavailable,missingMetadata,canRecover,onRecover={row->missingPanel=false;onRecover(row)},
                onInfo={missingInfo=it},onRemove={missingRemove=it},onDismiss={missingPanel=false},
                selecting=selecting,chosen=selectedPublicMembers.toSet(),enabled=!busy,onToggle=::toggleMember)
            val infoMember=unavailable.firstOrNull{it.id==missingInfo}
            if(infoMember!=null && activeUsable){
                val row=missingMetadata[infoMember.id]
                AlertDialog(onDismissRequest={missingInfo=null},title={Text("Информация о файле")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
                    Text(row?.originalName?:"Недоступный файл")
                    row?.let{Text("${it.mediaType.caption()} · ${it.providerId}");Text(date(it.createdAtEpochMillis));it.savedUri?.let{uri->Text(uri,style=MaterialTheme.typography.bodySmall)}}
                    Text("Файл недоступен. Связь с коллекцией сохранена.")
                }},confirmButton={TextButton({missingInfo=null}){Text("Закрыть")}})
            }
            val removeTarget=unavailable.firstOrNull{it.id==missingRemove}
            if(removeTarget!=null && activeUsable)AlertDialog(onDismissRequest={missingRemove=null},title={Text("Исключить из коллекции?")},
                text={Text("Удаляется только связь с коллекцией. История и файлы сохраняются.")},
                confirmButton={TextButton({missingRemove=null;removeMember(removeTarget.id)}){Text("Исключить")}},
                dismissButton={TextButton({missingRemove=null}){Text("Отмена")}})
            Column(Modifier.fillMaxWidth().onSizeChanged{onNoticeFooterHeight(it.height)}) {
            miniPlayer()
            if(selecting)Surface(color=MaterialTheme.colorScheme.surface){
                Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically){
                    Text("Выбрано: ${selectedPublicMembers.size}",Modifier.weight(1f))
                    TextButton({selecting=false;chosenMembers=arrayListOf()}){Text("Отмена")}
                    Button({removeMembers=true},enabled=activeUsable&&!busy&&selectedPublicMembers.isNotEmpty()){Text("Убрать")}
                }
            }
            }

        }
    }
    if(removeMembers&&collection!=null)AlertDialog(onDismissRequest={removeMembers=false},title={Text("Убрать файлы и ссылки?")},
        text={Text("Будут удалены только связи с коллекцией. Файлы останутся на месте.")},
        confirmButton={TextButton({removeMembers=false;mutate{repository.removeSelection(collection.record.id,selectedPublicMembers);chosenMembers=arrayListOf();selecting=false}},enabled=activeUsable&&!busy&&selectedPublicMembers.isNotEmpty()){Text("Убрать")}},
        dismissButton={TextButton({removeMembers=false}){Text("Отмена")}})
    if(linkDialog&&collection!=null)ValueDialog("Добавить ссылку","","HTTP(S) ссылка",{linkDialog=false}){repository.addLink(collection.record.id,it)}
    if(remove&&collection!=null)AlertDialog(onDismissRequest={remove=false},title={Text("Удалить коллекцию?")},
        text={Text("Будет удалена только подборка «${collection.record.name}». Все файлы, альбомы и плейлисты останутся.")},
        confirmButton={TextButton({remove=false;mutate{repository.delete(collection.record.id);withContext(Dispatchers.IO){CollectionCoverStore(context).automatic(collection.record.id)};selected=null}}){Text("Удалить",color=MaterialTheme.colorScheme.error)}},
        dismissButton={TextButton({remove=false}){Text("Отмена")}})
}

internal fun moveCollectionMember(order:List<String>,from:String,to:String):List<String>{
    val source=order.indexOf(from);val target=order.indexOf(to)
    if(source<0||target<0||source==target)return order
    return order.toMutableList().apply{add(target,removeAt(source))}
}

@Composable
private fun CollectionMemberMenu(onInfo:(()->Unit)?,onRemove:()->Unit,enabled:Boolean){
    var expanded by remember{mutableStateOf(false)}
    Box{
        IconButton({expanded=true},enabled=enabled){Icon(Icons.Rounded.MoreVert,"Действия файла")}
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

@Composable
private fun CollectionMusicGlyph(){
    val tint=MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(30.dp)){
        val w=size.width; val h=size.height
        val path=Path().apply{
            moveTo(w*.34f,h*.72f);lineTo(w*.34f,h*.22f);lineTo(w*.86f,h*.10f)
            lineTo(w*.86f,h*.60f);moveTo(w*.34f,h*.35f);lineTo(w*.86f,h*.23f)
        }
        drawPath(path,tint,style=Stroke(width=w*.085f,cap=StrokeCap.Round,join=StrokeJoin.Round))
        drawOval(tint,topLeft=Offset(w*.08f,h*.66f),size=androidx.compose.ui.geometry.Size(w*.27f,h*.19f),style=Stroke(w*.075f))
        drawOval(tint,topLeft=Offset(w*.60f,h*.54f),size=androidx.compose.ui.geometry.Size(w*.27f,h*.19f),style=Stroke(w*.075f))
    }
}

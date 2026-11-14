package com.domenota.medialoader.ui.screens.organization

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.*
import com.domenota.medialoader.ui.components.DownloadThumbnail
import com.domenota.medialoader.ui.model.MediaKind
import com.domenota.medialoader.ui.screens.audioArtist
import com.domenota.medialoader.ui.screens.audioTitle
import com.domenota.medialoader.ui.screens.gallery.GalleryAlbumFileOrder
import com.domenota.medialoader.ui.screens.gallery.GalleryAlbumFileSort
import com.domenota.medialoader.ui.screens.music.MusicLibraryTrackRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun collectionPickerKey(ref:LibraryMediaRef)="${ref.item.mediaType.name}:${ref.key}"

/** A disposable audition player: never sends queue commands to the music session. */
private class CollectionAudioPreview(context:Context) {
    private val context=context.applicationContext
    private val manager=context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var player:MediaPlayer?=null
    private var request:AudioFocusRequest?=null
    var current:String?=null;private set
    fun stop(){player?.release();player=null;request?.let(manager::abandonAudioFocusRequest);request=null;current=null}
    fun play(ref:LibraryMediaRef,onError:()->Unit,onComplete:()->Unit) {
        if(current==ref.key){stop();onComplete();return}
        stop()
        val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change->if(change<0){stop();onComplete()} }.build()
        if(manager.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){onError();return}
        request=focus
        val created=MediaPlayer()
        player=created;current=ref.key
        try {
            created.setAudioAttributes(attributes)
            created.setDataSource(context,Uri.parse(ref.item.savedUri))
            created.setOnPreparedListener{if(player===it)it.start()}
            created.setOnCompletionListener{stop();onComplete()}
            created.setOnErrorListener{_,_,_->stop();onError();true}
            created.prepareAsync()
        }catch(e:Exception){stop();onError()}
    }
}

@Composable
internal fun CollectionMediaPicker(title:String,refs:List<LibraryMediaRef>,existing:Set<String>,links:List<String>,existingLinks:Set<String>,
    audioOnly:Boolean,onBack:()->Unit,onSave:suspend (List<LibraryMediaRef>,List<String>)->Unit) {
    var query by rememberSaveable{mutableStateOf("")}
    var tab by rememberSaveable{mutableStateOf(if(audioOnly)2 else 0)}
    var album by rememberSaveable{mutableStateOf<String?>(null)}
    var sortName by rememberSaveable{mutableStateOf(GalleryAlbumFileSort.DATE_NEW.name)}
    var selection by rememberSaveable{mutableStateOf(arrayListOf<String>())}
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    var albumMenu by remember{mutableStateOf(false)}
    var sortMenu by remember{mutableStateOf(false)}
    var video by remember{mutableStateOf<LibraryMediaRef?>(null)}
    var audition by remember{mutableStateOf<String?>(null)}
    val context=LocalContext.current
    val audio=remember(context){CollectionAudioPreview(context)}
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(audio,lifecycle){
        val observer=androidx.lifecycle.LifecycleEventObserver{_,event->if(event==androidx.lifecycle.Lifecycle.Event.ON_STOP){audio.stop();audition=null;video=null}}
        lifecycle.addObserver(observer)
        onDispose{lifecycle.removeObserver(observer);audio.stop()}
    }
    val scope=rememberCoroutineScope()
    val public=remember(refs){refs.filter{MediaOrganizationPolicy.keys(it).isNotEmpty()}.distinctBy(::collectionPickerKey)}
    val allLinks=remember(links){links.distinct().filter{runCatching{MediaOrganizationPolicy.link(it)}.isSuccess}}
    val existingKeys=remember(existing,existingLinks){existing+existingLinks.map{"LINK:$it"}}
    val selected=selection.toSet()-existingKeys
    val latestSelection by rememberUpdatedState(selected)
    val albums=remember(public){public.mapNotNull{ref->ref.deviceAlbumId?.let{it to (ref.deviceAlbumName?:"Альбом")}}.distinctBy{it.first}}
    val type=listOf(MediaType.PHOTO,MediaType.VIDEO,MediaType.AUDIO).getOrNull(tab)
    val visible=remember(public,query,tab,album,sortName){
        val filtered=public.filter{it.item.mediaType==type&&(album==null||it.deviceAlbumId==album)&&
            (it.item.originalName.contains(query,true)||audioTitle(it.item).contains(query,true)||audioArtist(it.item).orEmpty().contains(query,true))}
        val byId=filtered.associateBy{it.item.id}
        GalleryAlbumFileOrder.sorted(filtered.map{it.item},GalleryAlbumFileSort.valueOf(sortName)).mapNotNull{byId[it.id]}
    }
    val visibleLinks=allLinks.filter{it.contains(query,true)}
    val visibleKeys=if(tab==3)visibleLinks.map{"LINK:$it"} else visible.map(::collectionPickerKey)
    val grid=rememberLazyGridState()
    fun toggle(key:String){if(!busy&&key !in existingKeys)selection=ArrayList(if(key in selected)selected-key else selected+key)}
    BackHandler{if(!busy){if(video!=null)video=null else onBack()}}
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("collection-media-picker")) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            IconButton(onBack,enabled=!busy){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")}
            Text("Добавить в $title",Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().padding(horizontal=12.dp),singleLine=true,label={Text("Поиск")},enabled=!busy)
        if(!audioOnly)ScrollableTabRow(tab){listOf("Фото","Видео","Музыка","Ссылки").forEachIndexed{i,label->Tab(tab==i,{tab=i;album=null;audio.stop();audition=null},text={Text(label)})}}
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
            if(tab!=3) {
                Box{TextButton({albumMenu=true},enabled=!busy){Text(albums.firstOrNull{it.first==album}?.second?:"Все альбомы")}
                    DropdownMenu(albumMenu,{albumMenu=false}){DropdownMenuItem({Text("Все альбомы")},{album=null;albumMenu=false});albums.forEach{(id,name)->DropdownMenuItem({Text(name)},{album=id;albumMenu=false})}}}
                Box{IconButton({sortMenu=true},enabled=!busy){Icon(Icons.Rounded.Sort,"Сортировка")}
                    DropdownMenu(sortMenu,{sortMenu=false}){GalleryAlbumFileSort.entries.forEach{sort->DropdownMenuItem({Text(sort.label)},{sortName=sort.name;sortMenu=false})}}}
            }
            Spacer(Modifier.weight(1f))
            TextButton({selection=ArrayList(selected+visibleKeys.filterNot(existingKeys::contains))},enabled=!busy&&visibleKeys.isNotEmpty()){Text("Выбрать все в фильтре")}
        }
        error?.let{Text(it,Modifier.padding(horizontal=12.dp),color=MaterialTheme.colorScheme.error)}
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(tab==0||tab==1) {
            var anchor by remember{mutableStateOf<String?>(null)}
            var baseline by remember{mutableStateOf(emptySet<String>())}
            LazyVerticalGrid(GridCells.Fixed(3),state=grid,modifier=Modifier.weight(1f).pointerInput(visibleKeys,existingKeys,busy){
                if(!busy)detectDragGesturesAfterLongPress(onDragStart={point->
                    val hit=grid.layoutInfo.visibleItemsInfo.firstOrNull{point.x>=it.offset.x&&point.x<it.offset.x+it.size.width&&point.y>=it.offset.y&&point.y<it.offset.y+it.size.height}
                    anchor=hit?.key as? String;baseline=latestSelection
                    anchor?.let{selection=ArrayList(collectionRangeSelection(baseline,visibleKeys,it,it,existingKeys))}
                },onDragEnd={anchor=null},onDragCancel={anchor=null}){change,_->
                    val hit=grid.layoutInfo.visibleItemsInfo.firstOrNull{change.position.x>=it.offset.x&&change.position.x<it.offset.x+it.size.width&&change.position.y>=it.offset.y&&change.position.y<it.offset.y+it.size.height}
                    val key=hit?.key as? String
                    if(anchor!=null&&key!=null){selection=ArrayList(collectionRangeSelection(baseline,visibleKeys,anchor!!,key,existingKeys));change.consume()}
                }
            },contentPadding=PaddingValues(8.dp),horizontalArrangement=Arrangement.spacedBy(3.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                items(visible,key=::collectionPickerKey){ref->
                    val key=collectionPickerKey(ref)
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clickable(enabled=!busy){toggle(key)}.testTag("picker-$key")) {
                        DownloadThumbnail(ref.item.savedUri,ref.item.previewUrl,if(tab==0)MediaKind.IMAGE else MediaKind.VIDEO,Modifier.fillMaxSize())
                        PickerMark(key in selected||key in existingKeys,key in existingKeys,Modifier.align(Alignment.TopEnd))
                        if(tab==1)Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha=.6f)),verticalAlignment=Alignment.CenterVertically){
                            IconButton({audio.stop();audition=null;video=ref},Modifier.size(36.dp)){Icon(Icons.Rounded.PlayArrow,"Предпросмотр видео",tint=Color.White)}
                            ref.item.durationMs?.let{Text("${it/60000}:${"%02d".format(it/1000%60)}",color=Color.White,style=MaterialTheme.typography.labelSmall)}
                        }
                    }
                }
                if(visible.isEmpty())item(span={GridItemSpan(3)}){Text("Нет доступных файлов",Modifier.padding(20.dp))}
            }
        } else LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(8.dp)) {
            if(tab==2)items(visible,key=::collectionPickerKey){ref->
                val key=collectionPickerKey(ref)
                MusicLibraryTrackRow(ref.item,onPlay={audio.play(ref,{error="Не удалось прослушать трек";audition=null},{audition=null});audition=audio.current},isCurrent=audition==ref.key,isPlaying=audition==ref.key) {
                    Checkbox(key in selected||key in existingKeys,{toggle(key)},enabled=!busy&&key !in existingKeys)
                }
            } else items(visibleLinks,key={it}){link->
                val key="LINK:$link"
                Row(Modifier.fillMaxWidth().clickable(enabled=!busy){toggle(key)}.padding(8.dp),verticalAlignment=Alignment.CenterVertically){
                    val metadata=public.firstOrNull{it.item.sourcePageUrl==link||it.item.sourceUrl==link}?.item
                    if(metadata!=null)DownloadThumbnail(metadata.savedUri,metadata.previewUrl,when(metadata.mediaType){MediaType.PHOTO->MediaKind.IMAGE;MediaType.VIDEO->MediaKind.VIDEO;else->MediaKind.AUDIO},Modifier.size(48.dp))
                    else Icon(Icons.Rounded.Link,null)
                    Column(Modifier.weight(1f).padding(8.dp)){Text(metadata?.originalName?:link,maxLines=2,overflow=TextOverflow.Ellipsis);Text(runCatching{java.net.URI(link).host}.getOrNull().orEmpty(),style=MaterialTheme.typography.labelSmall)}
                    Checkbox(key in selected||key in existingKeys,{toggle(key)},enabled=!busy&&key !in existingKeys)
                }
            }
            if(visibleKeys.isEmpty())item{Text("Нет доступных материалов",Modifier.padding(20.dp))}
        }
        Surface(tonalElevation=3.dp){Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Выбрано: ${selected.size}",Modifier.weight(1f),style=MaterialTheme.typography.labelLarge)
            TextButton({selection=arrayListOf()},enabled=!busy&&selected.isNotEmpty()){Text("Сбросить")}
            Button(enabled=!busy&&selected.isNotEmpty(),onClick={scope.launch{
                busy=true;error=null;audio.stop();audition=null
                try {
                    val media=public.filter{collectionPickerKey(it) in selected}
                    val pickedLinks=allLinks.filter{"LINK:$it" in selected}
                    check(media.size+pickedLinks.size==selected.size){"Часть файлов недоступна. Обновите выбор"}
                    withContext(Dispatchers.IO){media.forEach{ref->context.contentResolver.openAssetFileDescriptor(Uri.parse(ref.item.savedUri),"r")?.use{}?:error("Файл недоступен")}}
                    onSave(media,pickedLinks)
                }catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"Не удалось добавить"}finally{busy=false}
            }}){Text("Добавить (${selected.size})")}
        }}
    }
    video?.let{ref->Dialog({video=null},properties=DialogProperties(usePlatformDefaultWidth=false)){
        Box(Modifier.fillMaxSize().background(Color.Black)){
            AndroidView(factory={ctx->VideoView(ctx).apply{setMediaController(MediaController(ctx).also{it.setAnchorView(this)});setVideoURI(Uri.parse(ref.item.savedUri));setOnPreparedListener{start()}}},modifier=Modifier.fillMaxSize(),onRelease={it.stopPlayback()})
            IconButton({video=null},Modifier.align(Alignment.TopEnd).statusBarsPadding()){Icon(Icons.Rounded.Close,"Закрыть предпросмотр",tint=Color.White)}
        }
    }}
}
@Composable
private fun PickerMark(selected:Boolean,existing:Boolean,modifier:Modifier=Modifier) {
    Box(modifier.padding(6.dp).size(26.dp).clip(CircleShape).background(if(selected)MaterialTheme.colorScheme.primary else Color.Black.copy(alpha=.4f)),contentAlignment=Alignment.Center){
        Icon(if(selected)Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,if(existing)"Уже добавлено" else if(selected)"Выбрано" else "Выбрать",tint=if(selected)MaterialTheme.colorScheme.onPrimary else Color.White)
    }
}

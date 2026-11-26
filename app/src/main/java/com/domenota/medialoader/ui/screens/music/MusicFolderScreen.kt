package com.domenota.medialoader.ui.screens.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.ui.screens.audioTitle

@Composable
internal fun MusicFolderScreen(title:String,tracks:List<DownloadEntity>,excluded:Boolean,currentId:String?,playing:Boolean,
    onBack:()->Unit,onToggleVisibility:()->Unit,onPlay:(Boolean)->Unit,onPreview:(String)->Unit,
    onAddCollection:((List<String>)->Unit)?=null,
    onSimpleArchive:((List<String>)->Unit)?=null,
    onAddAllToPlaylist:((List<String>)->Unit)?=null,
    onRestoreOrder:(()->Unit)?=null,
    playBusy:Boolean=false,
    actions:@Composable (DownloadEntity)->Unit){
    var menu by remember{mutableStateOf(false)}
    var chosenIds by remember(title){mutableStateOf<Set<String>>(emptySet())}
    val eligible=remember(tracks){tracks.mapTo(mutableSetOf()){it.id}}
    val selected=chosenIds.intersect(eligible)
    LaunchedEffect(eligible) { chosenIds=chosenIds.intersect(eligible) }
    BackHandler(selected.isNotEmpty()){chosenIds=emptySet()}
    fun toggle(id:String) {
        if(id in eligible)chosenIds=if(id in chosenIds)chosenIds-id else chosenIds+id
    }
    val list=rememberLazyListState()
    Column(Modifier.fillMaxSize().testTag("music-folder-detail")){
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onBack){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")}
            Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,maxLines=2)
            Box {
                IconButton({menu=true}){Icon(Icons.Rounded.MoreVert,"Настройки папки")}
                DropdownMenu(menu,{menu=false}){
                    if(onAddAllToPlaylist!=null) DropdownMenuItem(
                        {Text("Добавить все треки в плейлист")},
                        {menu=false;onAddAllToPlaylist(tracks.map{it.id})},
                        enabled=!playBusy&&tracks.isNotEmpty()&&selected.isEmpty(),
                        leadingIcon={Icon(Icons.Rounded.PlaylistAdd,null)})
                    DropdownMenuItem({Text(if(excluded)"Отобразить в общем списке" else "Скрыть из общего списка")},
                        {menu=false;onToggleVisibility()},leadingIcon={Icon(if(excluded)Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,null)})
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){
            Icon(Icons.Rounded.Folder,null,Modifier.size(56.dp),tint=MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)){
                Row(verticalAlignment=Alignment.CenterVertically){
                    Text(com.domenota.medialoader.core.model.RussianCounts.tracks(tracks.size),
                        modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                    if(onRestoreOrder!=null) IconButton(onClick=onRestoreOrder,enabled=!playBusy&&selected.isEmpty(),
                        modifier=Modifier.size(40.dp).semantics{contentDescription="Восстановить исходный порядок"}) {
                        FolderOrderRestoreIcon()
                    }
                }
                Text(if(excluded)"Скрыта из общего списка" else "В общем списке",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Button({onPlay(false)},Modifier.weight(1f),enabled=!playBusy&&tracks.isNotEmpty()&&selected.isEmpty()){Icon(Icons.Rounded.PlayArrow,null);Text("Слушать")}
            OutlinedButton({onPlay(true)},Modifier.weight(1f),enabled=!playBusy&&tracks.isNotEmpty()&&selected.isEmpty()){Icon(Icons.Rounded.Shuffle,null);Text("Перемешать")}
        }
        if(tracks.isEmpty())Text("В папке нет доступных треков",Modifier.padding(16.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f).fillMaxWidth()){
            LazyColumn(Modifier.fillMaxSize().padding(end=if(tracks.size>5)musicFastScrollLaneWidth else 0.dp),state=list,contentPadding=PaddingValues(bottom=12.dp)){
                items(tracks,key={it.id}){track->
                    MusicLibraryTrackRow(track,
                        { if(selected.isNotEmpty())toggle(track.id) else onPreview(track.id) },
                        track.id==currentId,playing,
                        selectionMode=selected.isNotEmpty(),selected=track.id in selected,
                        onArtworkLongPress={ toggle(track.id) },
                        onToggleSelection={ toggle(track.id) }) { actions(track) }
                }
            }
            MusicFastScroll(list,tracks.map{audioTitle(it).take(1).uppercase()},Modifier.align(Alignment.CenterEnd))
        }
        if(selected.isNotEmpty()) {
            Surface(color=MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation=5.dp,modifier=Modifier.fillMaxWidth().testTag("music-folder-selection-footer")) {
                Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("Выбрано: ${selected.size}",Modifier.weight(1f))
                        IconButton({chosenIds=emptySet()}) { Icon(Icons.Rounded.Close,"Отменить выбор") }
                    }
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={
                        val confirmed=chosenIds.intersect(eligible).toList()
                        if(confirmed.isNotEmpty()&&onAddCollection!=null) {
                            onAddCollection(confirmed);chosenIds=emptySet()
                        }
                    },enabled=onAddCollection!=null,
                        modifier=Modifier.testTag("music-folder-selected-collection")) {
                        Icon(Icons.Rounded.CollectionsBookmark,null,Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Коллекция")
                    }
                    if (onSimpleArchive != null) TextButton({ onSimpleArchive(selected.toList()) },
                        modifier=Modifier.testTag("music-folder-selected-archive")) {
                        com.domenota.medialoader.ui.components.ArchiveActionIcon(null)
                        Spacer(Modifier.width(6.dp)); Text("В архив")
                    }
                    }
                }
            }
        }
    }
}

/** List-restart: original ordering, not a playback restart. */
@Composable
private fun FolderOrderRestoreIcon(){
    val tint=MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(24.dp)){
        val u=size.width/24f
        fun line(x1:Float,y1:Float,x2:Float,y2:Float)=drawLine(tint,Offset(x1*u,y1*u),Offset(x2*u,y2*u),2*u,StrokeCap.Round)
        line(3f,5f,19f,5f);line(3f,10f,12f,10f);line(3f,15f,8f,15f)
        drawArc(tint,220f,290f,false,topLeft=Offset(11*u,11*u),size=Size(10*u,10*u),style=Stroke(2*u,cap=StrokeCap.Round))
        line(11f,10f,11f,15f);line(11f,15f,16f,15f)
    }
}

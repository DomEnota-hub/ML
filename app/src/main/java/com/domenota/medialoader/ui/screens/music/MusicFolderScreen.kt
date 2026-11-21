package com.domenota.medialoader.ui.screens.music

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.ui.screens.audioTitle

@Composable
internal fun MusicFolderScreen(title:String,tracks:List<DownloadEntity>,excluded:Boolean,currentId:String?,playing:Boolean,
    onBack:()->Unit,onToggleVisibility:()->Unit,onPlay:(Boolean)->Unit,onPreview:(String)->Unit,
    onAddCollection:((List<String>)->Unit)?=null,
    onSimpleArchive:((List<String>)->Unit)?=null,
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
                    DropdownMenuItem({Text(if(excluded)"Отобразить в общем списке" else "Скрыть из общего списка")},
                        {menu=false;onToggleVisibility()},leadingIcon={Icon(if(excluded)Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,null)})
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){
            Icon(Icons.Rounded.Folder,null,Modifier.size(56.dp),tint=MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)){
                Text(com.domenota.medialoader.core.model.RussianCounts.tracks(tracks.size),style=MaterialTheme.typography.titleMedium)
                Text(if(excluded)"Скрыта из общего списка" else "В общем списке",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Button({onPlay(false)},Modifier.weight(1f),enabled=tracks.isNotEmpty()&&selected.isEmpty()){Icon(Icons.Rounded.PlayArrow,null);Text("Слушать")}
            OutlinedButton({onPlay(true)},Modifier.weight(1f),enabled=tracks.isNotEmpty()&&selected.isEmpty()){Icon(Icons.Rounded.Shuffle,null);Text("Перемешать")}
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
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=7.dp),
                    verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Выбрано: ${selected.size}",Modifier.weight(1f))
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
                    if (onSimpleArchive != null) IconButton({ onSimpleArchive(selected.toList()) },
                        modifier=Modifier.testTag("music-folder-selected-archive")) {
                        com.domenota.medialoader.ui.components.ArchiveActionIcon("В архив")
                    }
                    IconButton({chosenIds=emptySet()}) {
                        Icon(Icons.Rounded.Close,"Отменить выбор")
                    }
                }
            }
        }
    }
}

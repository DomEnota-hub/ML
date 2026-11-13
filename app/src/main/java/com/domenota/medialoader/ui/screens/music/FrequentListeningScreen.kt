package com.domenota.medialoader.ui.screens.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.playback.*
import com.domenota.medialoader.ui.screens.MusicTrackActions
import com.domenota.medialoader.ui.screens.audioTitle
import com.domenota.medialoader.ui.theme.musicPlaybackAccent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun FrequentListeningCard(count: Int, period: ListeningPeriod, onOpen: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal=17.dp,vertical=8.dp).clickable(onClick=onOpen)
        .testTag("frequent-playlist-card"),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(72.dp).background(musicPlaybackAccent.copy(alpha=.13f),RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center) {
                Icon(Icons.Rounded.GraphicEq,null,Modifier.size(44.dp),tint=musicPlaybackAccent)
            }
            Column(Modifier.weight(1f)) {
                Text("Часто слушаю",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                Text("Автоподборка · $count треков",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(period.label,style=MaterialTheme.typography.bodySmall,color=musicPlaybackAccent)
            }
            Icon(Icons.Rounded.ChevronRight,null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FrequentListeningScreen(selection: ListeningSelection, rows: Map<String,DownloadEntity>,
    period: ListeningPeriod, onPeriod: (ListeningPeriod)->Unit, loading: Boolean, error: String?,
    currentId: String?, playing: Boolean, favoriteIds: Set<String>,
    store: ListeningHistoryStore, onBack: ()->Unit, onPlay: (List<String>,Boolean)->Unit,
    onPreview: (String,List<String>)->Unit, onOpen: (String,List<String>)->Unit,
    onSave: suspend (String,List<String>)->Unit, onAdd: (String)->Unit,
    onShare: (String)->Unit, onDelete: (String)->Unit, onEnqueue: ((String,Boolean)->Unit)?,
    onFavorite: (suspend (String,Boolean)->Unit)?) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var excludedOpen by remember { mutableStateOf(false) }
    var clearOpen by remember { mutableStateOf(false) }
    var saveOpen by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var saveIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var operationError by remember { mutableStateOf<String?>(null) }
    val ranked = selection.ranked.filter { it.rowId in rows }
    val ids = ranked.map { it.rowId }
    fun mutate(action: suspend ()->Unit) { scope.launch {
        try { action(); operationError=null }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { operationError="Не удалось сохранить изменение" }
    } }
    fun exclude(entry: ListeningRank) { scope.launch {
        try {
            store.setExcluded(entry.trackId,true)
            if (snackbar.showSnackbar("Трек исключён из подборки","Отменить",duration=SnackbarDuration.Short)==SnackbarResult.ActionPerformed)
                store.setExcluded(entry.trackId,false)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { operationError="Не удалось изменить подборку" }
    } }
    Scaffold(Modifier.fillMaxSize(),contentWindowInsets=WindowInsets(0.dp,0.dp,0.dp,0.dp),
        snackbarHost={ SnackbarHost(snackbar) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("frequent-listening"),contentPadding=PaddingValues(bottom=12.dp)) {
            item(key="header") {
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад") }
                    Text("Часто слушаю",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    Box {
                        IconButton({ menu=true }) { Icon(Icons.Rounded.MoreVert,"Действия с автоподборкой") }
                        DropdownMenu(menu,{menu=false}) {
                            DropdownMenuItem({ Text("Сохранить как плейлист") },{menu=false; name="Часто слушаю · ${period.label}"; saveIds=ids.toList(); saveOpen=true},enabled=ids.isNotEmpty()&&!loading,leadingIcon={Icon(Icons.Rounded.PlaylistAdd,null)})
                            DropdownMenuItem({ Text("Исключённые треки") },{menu=false; excludedOpen=true},leadingIcon={Icon(Icons.Rounded.VisibilityOff,null)})
                            DropdownMenuItem({ Text("Очистить статистику") },{menu=false; clearOpen=true},leadingIcon={Icon(Icons.Rounded.DeleteSweep,null)})
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=17.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    ListeningPeriod.entries.forEach { entry ->
                        FilterChip(selected=entry==period,onClick={onPeriod(entry)},label={Text(entry.label)})
                    }
                }
                Surface(Modifier.fillMaxWidth().padding(horizontal=17.dp,vertical=10.dp),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                            Box(Modifier.size(64.dp).background(musicPlaybackAccent.copy(alpha=.13f),RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center) {
                                Icon(Icons.Rounded.GraphicEq,null,Modifier.size(40.dp),tint=musicPlaybackAccent)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("Твой топ",style=MaterialTheme.typography.titleLarge,color=musicPlaybackAccent)
                                Text("${ids.size} треков · ${period.label}",style=MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                            Button(onClick={onPlay(ids,false)},enabled=ids.isNotEmpty()&&!loading,modifier=Modifier.weight(1f)) {
                                Icon(Icons.Rounded.PlayArrow,null); Spacer(Modifier.width(6.dp)); Text("Слушать")
                            }
                            OutlinedButton(onClick={onPlay(ids,true)},enabled=ids.isNotEmpty()&&!loading) { Icon(Icons.Rounded.Shuffle,"Перемешать") }
                        }
                    }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal=17.dp))
                (operationError ?: error)?.let { Text(it,Modifier.padding(17.dp),color=MaterialTheme.colorScheme.error) }
                if (!loading && ids.isEmpty()) Text("Здесь появятся треки, которые ты прослушал не менее двух раз за выбранный период. Статистика собирается с момента обновления ML.",
                    Modifier.padding(17.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(ranked,key={it.trackId}) { entry ->
                val track=rows.getValue(entry.rowId)
                val id=entry.rowId
                MusicLibraryTrackRow(track,{onPreview(id,ids)},id==currentId,playing,
                    rank=ids.indexOf(id)+1,listeningCount=entry.plays) {
                    MusicTrackActions(track,onEnqueue?.let { action->{ next:Boolean->action(id,next) } },
                        {onOpen(id,ids)},{onAdd(id)},{onShare(id)},{onDelete(id)},null,null,null,
                        onExclude={exclude(entry)},favorite=id in favoriteIds,
                        onFavorite=onFavorite?.let { action->{mutate { action(id,id !in favoriteIds) }} })
                }
            }
        }
    }
    if (excludedOpen) AlertDialog(onDismissRequest={excludedOpen=false},title={Text("Исключённые треки")},text={
        LazyColumn(Modifier.heightIn(max=360.dp)) {
            if (selection.excluded.isEmpty()) item { Text("Нет исключённых треков") }
            items(selection.excluded,key={it.trackId}) { entry ->
                Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(entry.rowId?.let(rows::get)?.let(::audioTitle) ?: "Недоступный трек",Modifier.weight(1f))
                    IconButton({mutate { store.setExcluded(entry.trackId,false) }}) { Icon(Icons.Rounded.Restore,"Вернуть в подборку") }
                }
            }
        }
    },confirmButton={TextButton({excludedOpen=false}){Text("Закрыть")}},
        dismissButton={if(selection.excluded.isNotEmpty()) TextButton({mutate { store.restoreAll() }}){Text("Вернуть все")}})
    if (clearOpen) AlertDialog(onDismissRequest={clearOpen=false},title={Text("Очистить статистику?")},
        text={Text("Будет удалена история прослушиваний. Исключения, файлы, избранное и обычные плейлисты сохранятся.")},
        confirmButton={TextButton({clearOpen=false;mutate { store.clearHistory() }}){Text("Очистить",color=MaterialTheme.colorScheme.error)}},
        dismissButton={TextButton({clearOpen=false}){Text("Отмена")}})
    if (saveOpen) AlertDialog(onDismissRequest={if(!busy)saveOpen=false},title={Text("Сохранить как плейлист")},
        text={OutlinedTextField(name,{name=it},label={Text("Название")},singleLine=true,enabled=!busy)},
        confirmButton={TextButton(enabled=!busy&&name.isNotBlank()&&saveIds.isNotEmpty(),onClick={
            val snapshot=saveIds; val title=name.trim()
            scope.launch {
                busy=true
                try { onSave(title,snapshot); saveOpen=false; snackbar.showSnackbar("Плейлист сохранён") }
                catch(e:CancellationException){throw e}
                catch(_:Exception){operationError="Не удалось сохранить плейлист";saveOpen=false}
                finally{busy=false}
            }
        }){Text("Сохранить")}},dismissButton={TextButton({saveOpen=false},enabled=!busy){Text("Отмена")}})
}

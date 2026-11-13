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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.playback.*
import com.domenota.medialoader.ui.screens.MusicTrackActions
import com.domenota.medialoader.ui.screens.audioTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal enum class SmartMusicPage(val label: String) {
    FORGOTTEN("Давно не слушал"), MIX("Умный микс"), STATS("Музыкальная статистика");
    val icon: ImageVector get() = when(this) {
        FORGOTTEN -> Icons.Rounded.History
        MIX -> Icons.Rounded.Shuffle
        STATS -> Icons.Rounded.BarChart
    }
}

@Composable
internal fun SmartMusicCard(page: SmartMusicPage,onOpen: ()->Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal=17.dp,vertical=5.dp).clickable(onClick=onOpen)
        .testTag("smart-card-${page.name}"),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(13.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(13.dp)) {
            Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.primaryContainer,RoundedCornerShape(13.dp)),contentAlignment=Alignment.Center) {
                Icon(page.icon,null,Modifier.size(30.dp),tint=MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f)) {
                Text(page.label,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                Text(when(page){SmartMusicPage.FORGOTTEN->"Вернуться к любимой музыке"; SmartMusicPage.MIX->"Любимое и новые открытия"; SmartMusicPage.STATS->"Время, треки и исполнители"},
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight,null)
        }
    }
}

private fun listeningTime(ms: Long): String {
    val seconds=(ms/1000).coerceAtLeast(0)
    return if(seconds>=3600) "${seconds/3600} ч ${seconds/60%60} мин"
        else if(seconds>=60) "${seconds/60} мин ${seconds%60} с" else "$seconds с"
}

@Composable
private fun HistoryGraph(points: List<SmartChartPoint>,byTime:Boolean) {
    val maximum=points.maxOfOrNull { if(byTime) it.wallMs else it.plays }?.coerceAtLeast(1L) ?: 1L
    val barWidth=if(points.size>12) 28.dp else 42.dp
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp),verticalAlignment=Alignment.Bottom) {
        points.forEach { point ->
            val value=if(byTime) point.wallMs else point.plays
            val fraction=(value.toDouble()/maximum).coerceIn(0.0,1.0).toFloat()
            Column(Modifier.width(barWidth).semantics { contentDescription="${point.label}: ${if(byTime) listeningTime(value) else "$value прослушиваний"}" },
                horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Box(Modifier.height(100.dp).fillMaxWidth(),contentAlignment=Alignment.BottomCenter) {
                    Box(Modifier.width(15.dp).height((if(value>0) (100*fraction).coerceAtLeast(2f) else 1f).dp)
                        .background(if(value>0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(topStart=4.dp,topEnd=4.dp)))
                }
                Text(point.label,style=MaterialTheme.typography.labelSmall,maxLines=1)
            }
        }
    }
}

@Composable
private fun StatsMetric(label:String,value:String,modifier:Modifier=Modifier) {
    Surface(modifier,shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp)) {
            Text(value,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
            Text(label,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SmartMusicScreen(page:SmartMusicPage,snapshot:SmartMusicSnapshot,rows:Map<String,DownloadEntity>,
    period:SmartStatsPeriod,onPeriod:(SmartStatsPeriod)->Unit,diversity:Int,onDiversity:(Int)->Unit,
    forgottenDays:Long,onForgottenDays:(Long)->Unit,loading:Boolean,error:String?,
    currentId:String?,playing:Boolean,favoriteIds:Set<String>,onBack:()->Unit,onRefreshMix:()->Unit,
    onPlay:(List<String>,Boolean)->Unit,onPreview:(String,List<String>)->Unit,onOpen:(String,List<String>)->Unit,
    onSave:suspend (String,List<String>)->Unit,onAdd:(String)->Unit,onShare:(String)->Unit,onDelete:(String)->Unit,
    onEnqueue:((String,Boolean)->Unit)?,onFavorite:(suspend (String,Boolean)->Unit)?) {
    val scope=rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    var saveIds by remember { mutableStateOf<List<String>?>(null) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var byTime by remember { mutableStateOf(true) }
    var slider by remember(diversity) { mutableFloatStateOf(diversity.toFloat()) }
    val tracks=when(page){SmartMusicPage.FORGOTTEN->snapshot.forgotten; SmartMusicPage.MIX->snapshot.mix; SmartMusicPage.STATS->snapshot.topTracks}
        .filter {it.rowId in rows}
    val ids=tracks.map {it.rowId}
    fun mutate(action:suspend ()->Unit) { scope.launch {
        try {action(); problem=null} catch(e:CancellationException){throw e}
        catch(_:Exception){problem="Не удалось выполнить действие"}
    } }
    LazyColumn(Modifier.fillMaxSize().testTag("smart-music-${page.name}"),contentPadding=PaddingValues(bottom=12.dp)) {
        item(key="header") {
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onBack){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")}
                Text(page.label,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                if(page==SmartMusicPage.MIX) IconButton(onRefreshMix,enabled=!loading) { Icon(Icons.Rounded.Refresh,"Обновить микс") }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal=17.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                when(page) {
                    SmartMusicPage.FORGOTTEN -> {
                        Text("Ранее прослушанные треки. Сначала те, к которым ты возвращался чаще.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            listOf(30L,60L).forEach { days -> FilterChip(selected=days==forgottenDays,onClick={onForgottenDays(days)},label={Text("$days дней")}) }
                        }
                    }
                    SmartMusicPage.MIX -> {
                        Text("Разнообразие: ${slider.roundToInt()}",fontWeight=FontWeight.SemiBold)
                        Slider(value=slider,onValueChange={slider=(it/5).roundToInt()*5f},valueRange=0f..100f,steps=19,
                            onValueChangeFinished={onDiversity(slider.roundToInt())},modifier=Modifier.testTag("smart-mix-diversity"))
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text("Больше любимого",style=MaterialTheme.typography.bodySmall)
                            Text("Больше нового",style=MaterialTheme.typography.bodySmall)
                        }
                        val q=SmartMusicPolicy.quotas(slider.roundToInt())
                        Text("Цель: ${q[0]} привычных · ${q[1]} других · ${q[2]} забытых. При нехватке треков группы дополняются.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SmartMusicPage.STATS -> {
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                            SmartStatsPeriod.entries.forEach { p->FilterChip(selected=p==period,onClick={onPeriod(p)},label={Text(p.label)}) }
                        }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            StatsMetric("Время прослушивания",listeningTime(snapshot.wallMs),Modifier.weight(1f))
                            StatsMetric("Прослушиваний",snapshot.plays.toString(),Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            StatsMetric("Треков",snapshot.tracksPlayed.toString(),Modifier.weight(1f))
                            StatsMetric("Исполнителей",snapshot.artistsPlayed.toString(),Modifier.weight(1f))
                        }
                        Text("Динамика",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected=byTime,onClick={byTime=true},label={Text("Время")})
                            FilterChip(selected=!byTime,onClick={byTime=false},label={Text("Прослушивания")})
                        }
                        HistoryGraph(snapshot.chart,byTime)
                        Text("Время учитывается по последней дате записи сеанса. Статистика относится к доступной публичной медиатеке.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(snapshot.topArtists.isNotEmpty()) {
                            Text("Топ исполнителей",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                            snapshot.topArtists.take(10).forEachIndexed { index,artist ->
                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                    Text("${index+1}. ${artist.artist}",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                                    Text(artist.plays.toString(),color=MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        Text("Топ треков",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                    }
                }
                if(page!=SmartMusicPage.STATS) {
                    Text("${ids.size} треков",style=MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Button(onClick={onPlay(ids,false)},enabled=ids.isNotEmpty()&&!loading,modifier=Modifier.weight(1f)) {
                            Icon(Icons.Rounded.PlayArrow,null);Text("Слушать")
                        }
                        OutlinedButton(onClick={name=page.label;saveIds=ids.toList()},enabled=ids.isNotEmpty()&&!loading) {
                            Icon(Icons.Rounded.PlaylistAdd,"Сохранить плейлист")
                        }
                    }
                }
                if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                (problem?:error)?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                if(!loading&&ids.isEmpty()) Text(when(page){
                    SmartMusicPage.FORGOTTEN->"Пока нет треков, которые не слушали выбранное количество дней."
                    SmartMusicPage.MIX->"Нет доступных треков для микса."
                    SmartMusicPage.STATS->"Пока нет прослушиваний за этот период."
                },color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(tracks,key={it.rowId}) { entry ->
            val id=entry.rowId
            val row=rows.getValue(id)
            MusicLibraryTrackRow(row,{onPreview(id,ids)},id==currentId,playing,
                rank=ids.indexOf(id)+1,listeningCount=if(page==SmartMusicPage.STATS) entry.periodPlays else entry.plays) {
                MusicTrackActions(row,onEnqueue?.let { action->{ next:Boolean->action(id,next) } },
                    {onOpen(id,ids)},{onAdd(id)},{onShare(id)},{onDelete(id)},null,null,null,
                    favorite=id in favoriteIds,onFavorite=onFavorite?.let { action->{mutate {action(id,id !in favoriteIds)}} })
            }
        }
    }
    saveIds?.let { snapshotIds ->
        AlertDialog(onDismissRequest={if(!busy)saveIds=null},title={Text("Сохранить как плейлист")},
            text={OutlinedTextField(name,{name=it},label={Text("Название")},singleLine=true,enabled=!busy)},
            confirmButton={TextButton(enabled=!busy&&name.isNotBlank(),onClick={
                scope.launch {busy=true;try{onSave(name.trim(),snapshotIds);saveIds=null}
                    catch(e:CancellationException){throw e}catch(_:Exception){problem="Не удалось сохранить плейлист";saveIds=null}finally{busy=false}}
            }){Text("Сохранить")}},dismissButton={TextButton({saveIds=null},enabled=!busy){Text("Отмена")}})
    }
}

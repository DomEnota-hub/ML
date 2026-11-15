package com.domenota.medialoader.ui.screens.music

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.playback.*
import com.domenota.medialoader.ui.screens.audioArtist
import com.domenota.medialoader.ui.screens.audioTitle
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
internal fun MusicDayStatsScreen(interval:SmartStatsInterval,snapshot:SmartMusicSnapshot,rows:Map<String,DownloadEntity>,
    loading:Boolean,error:String?,currentId:String?,playing:Boolean,onBack:()->Unit,onDay:(LocalDate)->Unit,
    onPreview:(String,List<String>)->Unit,onExport:()->Unit,exporting:Boolean,exported:Boolean){
    var artist by rememberSaveable(interval.first.toString()){mutableStateOf<String?>(null)}
    val actual=if(snapshot.statsInterval==interval)snapshot else SmartMusicSnapshot()
    val all=actual.playedTracks.filter{it.rowId in rows}
    val artists=all.mapNotNull{audioArtist(rows.getValue(it.rowId))?.trim()?.takeIf(String::isNotEmpty)}.distinctBy{SmartMusicPolicy.artistKey(it)}
    val tracks=all.filter{artist==null||SmartMusicPolicy.artistKey(audioArtist(rows.getValue(it.rowId)))==artist}
    val ids=tracks.map{it.rowId}
    val zone=ZoneId.systemDefault()
    val today=LocalDate.now(zone)
    fun time(at:Long)=Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    fun date(at:Long)=Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    LazyColumn(Modifier.fillMaxSize().testTag("music-stats-day"),contentPadding=PaddingValues(bottom=16.dp)){
        item(key="day-header"){
            Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
                IconButton(onBack){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")}
                Text("Статистика дня",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    IconButton({onDay(interval.first.minusDays(1))},enabled=interval.first>LocalDate.of(1970,1,1)){Icon(Icons.Rounded.ChevronLeft,"Предыдущий день")}
                    Text(interval.title,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton({onDay(interval.first.plusDays(1))},enabled=interval.first<today){Icon(Icons.Rounded.ChevronRight,"Следующий день")}
                }
                if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                if(!loading){
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        StatsMetric("Время прослушивания",listeningTime(actual.wallMs),Modifier.weight(1f))
                        StatsMetric("Прослушиваний",actual.plays.toString(),Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        StatsMetric("Треков",actual.tracksPlayed.toString(),Modifier.weight(1f))
                        StatsMetric("Исполнителей",actual.artistsPlayed.toString(),Modifier.weight(1f))
                    }
                    if(actual.topArtists.isNotEmpty()){
                        Text("Топ исполнителей",style=MaterialTheme.typography.titleMedium)
                        actual.topArtists.forEachIndexed{index,entry->
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                                Text("${index+1}. ${entry.artist}",Modifier.weight(1f));Text(entry.plays.toString(),color=MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    val ranked=actual.topTracks.filter{it.rowId in rows}
                    if(ranked.isNotEmpty()){
                        Text("Топ треков",style=MaterialTheme.typography.titleMedium)
                        ranked.take(5).forEachIndexed{index,entry->
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                                Text("${index+1}. ${audioTitle(rows.getValue(entry.rowId))}",Modifier.weight(1f),maxLines=2,
                                    overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(entry.periodPlays.toString(),color=MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    OutlinedButton(onExport,Modifier.fillMaxWidth(),enabled=!exporting&&(actual.plays>0||actual.wallMs>0)){
                        Icon(Icons.Rounded.Save,null);Spacer(Modifier.width(8.dp));Text("Сохранить итог изображением")
                    }
                    if(exporting)LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(exported)Text("Изображение сохранено",color=MaterialTheme.colorScheme.primary)
                    if(all.isEmpty())Text("За этот день нет данных о прослушивании",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    else {
                        Text("Треки за день",style=MaterialTheme.typography.titleMedium)
                        if(artists.isNotEmpty())Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            FilterChip(artist==null,{artist=null},label={Text("Все")})
                            artists.forEach{value->val key=SmartMusicPolicy.artistKey(value);FilterChip(artist==key,{artist=key},label={Text(value)})}
                        }
                        Text("Список включает и неполные прослушивания. В рейтинг входят только засчитанные.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if(!loading){
            items(tracks,key={"day-track:"+it.rowId}){entry->
                val row=rows.getValue(entry.rowId)
                MusicLibraryTrackRow(row,{onPreview(row.id,ids)},row.id==currentId,playing,listeningCount=entry.periodPlays){
                    Text(listeningTime(entry.periodWallMs),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val sessions=actual.daySessions.filter{it.rowId in ids}
            if(sessions.isNotEmpty())item(key="history-title"){Text("История сеансов",Modifier.padding(16.dp),style=MaterialTheme.typography.titleMedium)}
            items(sessions,key={"session:"+it.sessionId}){entry->
                val row=rows.getValue(entry.rowId)
                Column(Modifier.fillMaxWidth().padding(horizontal=17.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                    val timeLabel=when{
                        date(entry.startedAt)==interval.first->"Начало ${time(entry.startedAt)}"
                        entry.firstMinute!=null->"Время записано с ${time(entry.firstMinute)}"
                        entry.creditedAt!=null->"Засчитано в ${time(entry.creditedAt)}"
                        date(entry.lastAt)==interval.first->"Последняя запись ${time(entry.lastAt)}"
                        else->"Сеанс начат раньше"
                    }
                    Text(timeLabel,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                    Text(audioTitle(row),style=MaterialTheme.typography.bodyLarge)
                    audioArtist(row)?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                    Text(listeningTime(entry.wallMs)+if(entry.creditedAt!=null)" · Засчитано прослушивание" else "",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(entry.legacyAttribution)Text("Часть времени относится к последней сохранённой дате; точной разбивки нет.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider()
                }
            }
        }
    }
}

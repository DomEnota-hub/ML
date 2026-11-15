package com.domenota.medialoader.ui.screens.music

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.RussianCounts
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun RecentMusicCard(onOpen: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal=17.dp,vertical=5.dp).clickable(onClick=onOpen)
        .testTag("recent-music-card"),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(13.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(13.dp)) {
            Icon(Icons.Rounded.CalendarToday,null,Modifier.size(48.dp),tint=MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text("Недавно добавленные",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                Text("История добавления по датам",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight,null)
        }
    }
}

@Composable
internal fun RecentMusicScreen(rows:List<DownloadEntity>,loading:Boolean,currentId:String?,playing:Boolean,onBack:()->Unit,
    onPlay:(List<String>,Boolean)->Unit,onPreview:(String,List<String>)->Unit,
    actions:@Composable (DownloadEntity,List<String>)->Unit) {
    val zone=ZoneId.systemDefault()
    val today=LocalDate.now(zone)
    val ru=Locale.forLanguageTag("ru-RU")
    val dateFormat=DateTimeFormatter.ofPattern("d MMMM yyyy",ru)
    fun dateLabel(date:LocalDate?):String=when(date){null->"Дата неизвестна";today->"Сегодня, ${date.format(dateFormat)}";today.minusDays(1)->"Вчера, ${date.format(dateFormat)}";else->date.format(dateFormat)}
    var search by rememberSaveable{mutableStateOf("")}
    var calendar by rememberSaveable{mutableStateOf(false)}
    var chosen by rememberSaveable{mutableStateOf<String?>(null)}
    var monthKey by rememberSaveable{mutableStateOf<String?>(null)}
    var expanded by rememberSaveable{mutableStateOf<String?>(null)}
    var detail by rememberSaveable{mutableStateOf<String?>(null)}
    val days=remember(rows,search,zone){RecentMusicPolicy.days(rows.filter { row ->
        search.isBlank() || listOfNotNull(row.originalName,row.recognizedArtist,row.recognizedTitle,row.sourceAudioArtist,row.sourceAudioTitle).any{it.contains(search,true)}
    },zone)}
    val dates=remember(days){days.mapNotNull{it.date}.toSet()}
    val picked=RecentMusicPolicy.selectedDay(days,chosen?.let{runCatching{LocalDate.parse(it)}.getOrNull()})
    val month=monthKey?.let{runCatching{YearMonth.parse(it)}.getOrNull()} ?: picked?.let(YearMonth::from) ?: YearMonth.from(today)
    val ids=remember(days){days.flatMap{d->d.tracks.map{it.id}}}
    fun key(day:RecentMusicDay)=day.date?.toString()?:"unknown"
    val detailDay=days.firstOrNull{key(it)==detail}
    val dayList=rememberLazyListState()
    val calendarList=rememberLazyListState()
    val detailList=androidx.compose.runtime.key(detail){rememberLazyListState()}
    LaunchedEffect(days,loading){if(!loading&&detail!=null&&detailDay==null)detail=null}
    BackHandler(detail!=null){detail=null}
    fun close(){if(detail!=null)detail=null else onBack()}
    Column(Modifier.fillMaxSize().testTag("recent-music-screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton({close()}){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")}
            Text(if(detailDay==null)"Недавно добавленные" else dateLabel(detailDay.date),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,maxLines=2)
        }
        if(detailDay==null){
            OutlinedTextField(search,{search=it},Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp),singleLine=true,
                label={Text("Поиск музыки")},leadingIcon={Icon(Icons.Rounded.Search,null)})
            if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
            Surface(Modifier.fillMaxWidth().padding(16.dp,8.dp),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceVariant){
                Column(Modifier.padding(13.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                        Icon(Icons.Rounded.CalendarToday,null,Modifier.size(38.dp),tint=MaterialTheme.colorScheme.primary)
                        Column{Text("История добавления",style=MaterialTheme.typography.titleMedium);Text(RussianCounts.tracks(ids.size),color=MaterialTheme.colorScheme.primary)}
                    }
                    RecentPlayButtons(ids,onPlay)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                FilterChip(!calendar,{calendar=false},{Text("По дням")},modifier=Modifier.weight(1f))
                FilterChip(calendar,{calendar=true},{Text("Календарь")},modifier=Modifier.weight(1f))
            }
        }
        val currentDetail=detailDay
        if(currentDetail!=null){
            val queue=remember(currentDetail){currentDetail.tracks.map{it.id}}
            Column(Modifier.padding(horizontal=16.dp,vertical=8.dp)){
                Text(RussianCounts.tracks(queue.size),color=MaterialTheme.colorScheme.onSurfaceVariant)
                RecentPlayButtons(queue,onPlay)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=detailList,contentPadding=PaddingValues(bottom=12.dp)){
                items(currentDetail.tracks,key={it.id}){track->MusicLibraryTrackRow(track,{onPreview(track.id,queue)},track.id==currentId,playing){actions(track,queue)}}
            }
        } else if(calendar){
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal=16.dp),state=calendarList,contentPadding=PaddingValues(bottom=16.dp)){
                item(key="calendar"){
                    Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceVariant){
                        Column(Modifier.padding(8.dp)){
                            Row(verticalAlignment=Alignment.CenterVertically){
                                IconButton({monthKey=month.minusMonths(1).toString()},enabled=dates.any{YearMonth.from(it)<month}){Icon(Icons.Rounded.ChevronLeft,"Предыдущий месяц")}
                                Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy",ru)),Modifier.weight(1f),textAlign=TextAlign.Center,style=MaterialTheme.typography.titleMedium)
                                IconButton({monthKey=month.plusMonths(1).toString()},enabled=dates.any{YearMonth.from(it)>month}){Icon(Icons.Rounded.ChevronRight,"Следующий месяц")}
                            }
                            Row(Modifier.fillMaxWidth()){listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс").forEach{Text(it,Modifier.weight(1f),textAlign=TextAlign.Center,style=MaterialTheme.typography.labelSmall)}}
                            RecentMusicPolicy.calendarCells(month).chunked(7).forEach{week->Row(Modifier.fillMaxWidth()){
                                week.forEach{date->Box(Modifier.weight(1f).height(42.dp),contentAlignment=Alignment.Center){
                                    if(date!=null){val enabled=date in dates;val selected=date==picked
                                        Surface(Modifier.size(37.dp).clickable(enabled=enabled){chosen=date.toString()},shape=RoundedCornerShape(10.dp),
                                            color=if(selected)MaterialTheme.colorScheme.primary else if(enabled)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant){
                                            Box(contentAlignment=Alignment.Center){Text(date.dayOfMonth.toString(),color=when{selected->MaterialTheme.colorScheme.onPrimary;enabled->MaterialTheme.colorScheme.onPrimaryContainer;else->MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.45f)})}
                                        }
                                    }
                                }}
                            }}
                        }
                    }
                }
                val day=days.firstOrNull{it.date==picked&&picked!=null&&YearMonth.from(picked)==month}
                if(day!=null)item(key="calendar-day"){
                    val queue=day.tracks.map{it.id}
                    Column(Modifier.padding(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(dateLabel(day.date),style=MaterialTheme.typography.titleMedium);Text(RussianCounts.tracks(queue.size))};IconButton({detail=key(day)}){Icon(Icons.Rounded.ChevronRight,"Открыть день")}}
                        RecentPlayButtons(queue,onPlay)
                        OutlinedButton({detail=key(day)},Modifier.fillMaxWidth()){Text("Показать все треки")}
                    }
                }else item(key="calendar-empty"){Text("В этом месяце нет выбранной даты с музыкой",Modifier.padding(vertical=16.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal=16.dp),state=dayList,contentPadding=PaddingValues(bottom=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                if(days.isEmpty()&&!loading)item(key="empty"){Text(if(search.isBlank())"Нет добавленных треков" else "Треки не найдены",Modifier.padding(16.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
                days.forEach{day->val k=key(day);val open=expanded==k||(expanded==null&&day==days.firstOrNull());val queue=day.tracks.map{it.id}
                    item(key="heading:$k"){
                        Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceVariant){
                            Row(Modifier.fillMaxWidth().clickable{expanded=if(open)"closed" else k}.padding(13.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                                Icon(Icons.Rounded.CalendarToday,null,tint=MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)){Text(dateLabel(day.date),style=MaterialTheme.typography.titleMedium);Text(RussianCounts.tracks(queue.size),color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)}
                                Icon(if(open)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,if(open)"Свернуть" else "Развернуть")
                            }
                        }
                    }
                    if(open){
                        item(key="play:$k"){RecentPlayButtons(queue,onPlay)}
                        items(day.tracks.take(3),key={"preview:$k:${it.id}"}){track->MusicLibraryTrackRow(track,{onPreview(track.id,queue)},track.id==currentId,playing){actions(track,queue)}}
                        if(day.tracks.size>3)item(key="all:$k"){OutlinedButton({detail=k},Modifier.fillMaxWidth()){Text("Показать все ${RussianCounts.tracks(queue.size)}")}}
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentPlayButtons(ids:List<String>,onPlay:(List<String>,Boolean)->Unit){
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        Button({onPlay(ids,false)},Modifier.weight(1f),enabled=ids.isNotEmpty()){Icon(Icons.Rounded.PlayArrow,null);Text("Слушать")}
        OutlinedButton({onPlay(ids,true)},Modifier.weight(1f),enabled=ids.isNotEmpty(),contentPadding=PaddingValues(horizontal=8.dp)){Icon(Icons.Rounded.Shuffle,null,Modifier.size(18.dp));Spacer(Modifier.width(4.dp));Text("Перемешать",maxLines=1)}
    }
}

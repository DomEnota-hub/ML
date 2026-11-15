package com.domenota.medialoader.ui.screens.music

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import com.domenota.medialoader.ui.components.CalendarDatesOutline
import com.domenota.medialoader.ui.theme.musicPlaybackAccent
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
    MusicOverviewCard("Недавно добавленные","История добавления по датам",com.domenota.medialoader.ui.components.CalendarDatesOutline,"recent-music-card",onOpen)
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
    var searchOpen by rememberSaveable{mutableStateOf(false)}
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
    BackHandler(detail!=null||searchOpen){if(detail!=null)detail=null else {searchOpen=false;search=""}}
    fun close(){if(detail!=null)detail=null else if(searchOpen){searchOpen=false;search=""} else onBack()}
    Column(Modifier.fillMaxSize().testTag("recent-music-screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton({close()}){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")}
            Text(if(detailDay==null)"Недавно добавленные" else dateLabel(detailDay.date),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,maxLines=2)
            if(detailDay==null)IconButton({searchOpen=!searchOpen;if(!searchOpen)search=""},Modifier.testTag("recent-search-toggle")){
                Icon(if(searchOpen)Icons.Rounded.Close else Icons.Rounded.Search,if(searchOpen)"Закрыть поиск" else "Поиск музыки")
            }
        }
        if(detailDay==null){
            if(searchOpen)OutlinedTextField(search,{search=it},Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp).testTag("recent-search-input"),singleLine=true,
                label={Text("Поиск музыки")},leadingIcon={Icon(Icons.Rounded.Search,null)})
            if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
            Surface(Modifier.fillMaxWidth().padding(16.dp,8.dp),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceVariant){
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                        Box(Modifier.size(42.dp).background(musicPlaybackAccent.copy(alpha=.13f),RoundedCornerShape(10.dp)),contentAlignment=Alignment.Center){
                            Icon(CalendarDatesOutline,null,Modifier.size(28.dp),tint=musicPlaybackAccent)
                        }
                        Column(Modifier.weight(1f)){Text("История добавления",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold);Text(if(loading&&rows.isEmpty())"Загрузка музыки…" else RussianCounts.tracks(ids.size),color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)}
                    }
                    RecentPlayButtons(ids,onPlay)
                }
            }
            RecentModeSwitch(calendar){calendar=it}
        }
        val currentDetail=detailDay
        if(currentDetail!=null){
            val queue=remember(currentDetail){currentDetail.tracks.map{it.id}}
            Column(Modifier.padding(horizontal=16.dp,vertical=8.dp)){
                Text(RussianCounts.tracks(queue.size),color=MaterialTheme.colorScheme.onSurfaceVariant)
                RecentPlayButtons(queue,onPlay)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=detailList,contentPadding=PaddingValues(bottom=12.dp)){
                items(currentDetail.tracks,key={it.id}){track->MusicLibraryTrackRow(track,{onPreview(track.id,queue)},track.id==currentId,playing,
                    secondaryTime=recentAdditionTime(track,zone),showDuration=false){actions(track,queue)}}
            }
        } else if(calendar){
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal=16.dp).testTag("recent-calendar-list"),state=calendarList,contentPadding=PaddingValues(bottom=16.dp)){
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
                                week.forEach{date->Box(Modifier.weight(1f).height(48.dp),contentAlignment=Alignment.Center){
                                    if(date!=null){val enabled=date in dates;val selected=date==picked
                                        Box(Modifier.fillMaxSize().testTag("recent-date-$date").clip(RoundedCornerShape(10.dp)).clickable(enabled=enabled){chosen=date.toString()},contentAlignment=Alignment.Center){
                                        Surface(Modifier.size(37.dp),shape=RoundedCornerShape(10.dp),
                                            color=if(selected)MaterialTheme.colorScheme.primary else if(enabled)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant){
                                            Box(contentAlignment=Alignment.Center){Text(date.dayOfMonth.toString(),color=when{selected->MaterialTheme.colorScheme.onPrimary;enabled->MaterialTheme.colorScheme.onPrimaryContainer;else->MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.45f)})}
                                        }
                                        }
                                    }
                                }}
                            }}
                        }
                    }
                }
                val day=days.firstOrNull{it.date==picked&&picked!=null&&YearMonth.from(picked)==month}
                if(day!=null)item(key="calendar-day"){
                    RecentDayCard(day,dateLabel(day.date),true,{}, {detail=key(day)},onPlay,onPreview,actions,currentId,playing,zone,
                        forceOpenDay=true,modifier=Modifier.padding(top=8.dp))
                }else item(key="calendar-empty"){Text("В этом месяце нет выбранной даты с музыкой",Modifier.padding(vertical=16.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal=16.dp),state=dayList,contentPadding=PaddingValues(bottom=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                if(days.isEmpty()&&!loading)item(key="empty"){Text(if(search.isBlank())"Нет добавленных треков" else "Треки не найдены",Modifier.padding(16.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
                items(days,key={"heading:${key(it)}"}){day->
                    val k=key(day);val open=expanded==k||(expanded==null&&day==days.firstOrNull())
                    RecentDayCard(day,dateLabel(day.date),open,{expanded=if(open)"closed" else k},{detail=k},onPlay,onPreview,
                        actions,currentId,playing,zone)
                }
            }
        }
    }
}

@Composable
private fun RecentPlayButtons(ids:List<String>,onPlay:(List<String>,Boolean)->Unit,day:Boolean=false){
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        Button({onPlay(ids,false)},Modifier.weight(1f),enabled=ids.isNotEmpty(),contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp)){
            Icon(Icons.Rounded.PlayArrow,null,Modifier.size(18.dp));Spacer(Modifier.width(4.dp));Text(if(day)"Слушать день" else "Слушать")
        }
        OutlinedButton({onPlay(ids,true)},Modifier.weight(1f),enabled=ids.isNotEmpty(),contentPadding=PaddingValues(horizontal=8.dp)){Icon(Icons.Rounded.Shuffle,null,Modifier.size(18.dp));Spacer(Modifier.width(4.dp));Text("Перемешать")}
    }
}


@Composable
private fun RecentModeSwitch(calendar:Boolean,onChange:(Boolean)->Unit){
    val shape=RoundedCornerShape(12.dp)
    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp).clip(shape)
        .background(MaterialTheme.colorScheme.surfaceVariant).padding(3.dp).selectableGroup(),
        horizontalArrangement=Arrangement.spacedBy(3.dp)){
        listOf(false,true).forEach{isCalendar->
            val selected=calendar==isCalendar
            Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                .background(if(selected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                .selectable(selected=selected,onClick={onChange(isCalendar)},role=Role.Tab)
                .heightIn(min=48.dp).testTag(if(isCalendar)"recent-calendar-mode" else "recent-days-mode")
                .padding(horizontal=6.dp,vertical=6.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){
                val tint=if(selected)MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                Icon(if(isCalendar)CalendarDatesOutline else Icons.Rounded.List,null,Modifier.size(18.dp),tint=tint)
                Spacer(Modifier.width(6.dp))
                Text(if(isCalendar)"Календарь" else "По дням",style=MaterialTheme.typography.labelLarge,color=tint)
            }
        }
    }
}

internal fun recentAdditionTime(track:DownloadEntity,zone:ZoneId):String? = track.createdAtEpochMillis.takeIf{it>0L}
    ?.let{Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))}

@Composable
private fun RecentDayCard(day:RecentMusicDay,label:String,expanded:Boolean,onToggle:()->Unit,onOpen:()->Unit,
    onPlay:(List<String>,Boolean)->Unit,onPreview:(String,List<String>)->Unit,
    actions:@Composable (DownloadEntity,List<String>)->Unit,currentId:String?,playing:Boolean,zone:ZoneId,
    forceOpenDay:Boolean=false,modifier:Modifier=Modifier){
    val key=day.date?.toString()?:"unknown"
    val shape=RoundedCornerShape(16.dp)
    Surface(modifier.fillMaxWidth().clip(shape),shape=shape,color=MaterialTheme.colorScheme.surfaceVariant){
        Column(Modifier.fillMaxWidth()){
            Row(Modifier.fillMaxWidth().testTag("recent-day-$key").then(if(forceOpenDay)Modifier else Modifier.clickable(onClick=onToggle))
                .heightIn(min=64.dp).padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Box(Modifier.widthIn(min=42.dp).heightIn(min=42.dp).background(musicPlaybackAccent.copy(alpha=.13f),RoundedCornerShape(10.dp))
                    .padding(3.dp),contentAlignment=Alignment.Center){
                    if(day.date!=null)Column(horizontalAlignment=Alignment.CenterHorizontally){
                        Text(day.date.dayOfMonth.toString(),color=musicPlaybackAccent,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                        Text(day.date.format(DateTimeFormatter.ofPattern("MMM",Locale.forLanguageTag("ru-RU"))).trimEnd('.'),color=musicPlaybackAccent,style=MaterialTheme.typography.labelSmall,maxLines=1)
                    }else Icon(CalendarDatesOutline,null,Modifier.size(24.dp),tint=musicPlaybackAccent)
                }
                Column(Modifier.weight(1f)){
                    Text(label,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                    Text(RussianCounts.tracks(day.tracks.size),color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                }
                if(!forceOpenDay)Icon(if(expanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,if(expanded)"Свернуть" else "Развернуть")
            }
            if(expanded){
                val queue=remember(day){day.tracks.map{it.id}}
                HorizontalDivider(Modifier.padding(horizontal=12.dp))
                Box(Modifier.padding(horizontal=12.dp,vertical=4.dp)){RecentPlayButtons(queue,onPlay,day=true)}
                day.tracks.take(3).forEach{track->
                    androidx.compose.runtime.key(track.id){MusicLibraryTrackRow(track,{onPreview(track.id,queue)},track.id==currentId,playing,
                        compact=true,secondaryTime=recentAdditionTime(track,zone),showDuration=false){actions(track,queue)}}
                }
                if(forceOpenDay||day.tracks.size>3){
                    HorizontalDivider(Modifier.padding(horizontal=12.dp))
                    TextButton(onOpen,Modifier.fillMaxWidth().testTag(if(forceOpenDay)"recent-open-day" else "recent-open-$key")){
                        Text("Показать все ${RussianCounts.tracks(queue.size)}",color=musicPlaybackAccent)
                        Icon(Icons.Rounded.ChevronRight,null,Modifier.size(18.dp),tint=musicPlaybackAccent)
                    }
                }
            }
        }
    }
}

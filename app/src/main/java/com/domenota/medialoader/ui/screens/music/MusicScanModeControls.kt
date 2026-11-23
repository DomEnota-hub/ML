package com.domenota.medialoader.ui.screens.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.library.DuplicateBackgroundScan.Outcome

/** "Continue" is never a synonym for choosing a new scan scope. */
internal fun shouldResumeMusicScan(unfinished:Boolean,selectedExpanded:Boolean,storedExpanded:Boolean):Boolean =
    unfinished && selectedExpanded==storedExpanded

internal fun musicScanModeName(expanded:Boolean)=if(expanded)"Расширенный поиск" else "Быстрый поиск"
internal fun musicScanActionLabel(expanded:Boolean,unfinished:Boolean,unfinishedExpanded:Boolean):String =
    (if(unfinished&&expanded==unfinishedExpanded)"Продолжить " else "Запустить ")+musicScanModeName(expanded).lowercase()

@Composable
internal fun MusicScanModeControls(expanded:Boolean,onMode:(Boolean)->Unit,unfinished:Boolean,unfinishedExpanded:Boolean,onStart:()->Unit,enabled:Boolean){
    Column(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
        Text("Режим поиска",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            FilterChip(!expanded,{onMode(false)},label={Text("Быстрый")},modifier=Modifier.weight(1f).testTag("duplicate-fast-mode"),enabled=enabled,
                leadingIcon={Icon(Icons.Rounded.Bolt,null,Modifier.size(18.dp))})
            FilterChip(expanded,{onMode(true)},label={Text("Расширенный")},modifier=Modifier.weight(1f).testTag("duplicate-full-mode"),enabled=enabled,
                leadingIcon={Icon(Icons.Rounded.GraphicEq,null,Modifier.size(18.dp))})
        }
        Text(if(expanded)"Проверяет аудио целиком. Требует больше времени." else "Проверяет характерные фрагменты длинных треков. Быстрее.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(unfinished&&expanded!=unfinishedExpanded)Text("Сохранён незавершённый ${musicScanModeName(unfinishedExpanded).lowercase()}",style=MaterialTheme.typography.bodySmall)
        Button(onStart,Modifier.fillMaxWidth().testTag("duplicate-start-mode"),enabled=enabled){Text(musicScanActionLabel(expanded,unfinished,unfinishedExpanded))}
    }
}

@Composable
internal fun MusicScanStatusCard(outcome:Outcome,partialGroups:Int?,error:String?){
    if(outcome==Outcome.IDLE&&error==null)return
    val state=if(error!=null)Outcome.FAILED else outcome
    if(state==Outcome.FAILED){
        com.domenota.medialoader.ui.components.DuplicateIssueNotice(
            error ?: "Не удалось завершить поиск. Повторите проверку.")
        return
    }
    val dark=MaterialTheme.colorScheme.background.luminance()<.5f
    val accent=when(state){
        Outcome.PAUSED->if(dark)Color(0xFF90BFFF) else Color(0xFF245B91)
        Outcome.STOPPED,Outcome.INTERRUPTED->if(dark)Color(0xFFFFD17B) else Color(0xFF805500)
        Outcome.COMPLETED->if(dark)Color(0xFF7ED9B1) else Color(0xFF236B4C)
        Outcome.FAILED->MaterialTheme.colorScheme.error
        else->MaterialTheme.colorScheme.primary
    }
    val title=when(state){Outcome.PAUSED->"Поиск приостановлен";Outcome.STOPPED->"Поиск остановлен";Outcome.INTERRUPTED->"Поиск прерван";Outcome.COMPLETED->"Поиск завершён";Outcome.FAILED->"Не удалось завершить поиск";Outcome.RUNNING->"Выполняется поиск";Outcome.IDLE->"Поиск"}
    val icon=when(state){Outcome.PAUSED->Icons.Rounded.PauseCircleOutline;Outcome.STOPPED,Outcome.INTERRUPTED->Icons.Rounded.StopCircle;Outcome.COMPLETED->Icons.Rounded.CheckCircle;Outcome.FAILED->Icons.Rounded.ErrorOutline;else->Icons.Rounded.Search}
    Surface(Modifier.fillMaxWidth().padding(vertical=4.dp).testTag("music-scan-status"),shape=RoundedCornerShape(14.dp),color=accent.copy(alpha=if(dark).12f else .09f)){
        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
            Icon(icon,null,tint=accent,modifier=Modifier.size(30.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){
                Text(title,color=accent,style=MaterialTheme.typography.titleSmall)
                val message=when(state){Outcome.PAUSED,Outcome.STOPPED,Outcome.INTERRUPTED->if(partialGroups!=null)"Предварительные результаты сохранены. Групп: $partialGroups" else "Можно продолжить проверку. Сохранённые отпечатки используются повторно."
                    Outcome.COMPLETED->"Результаты доступны в журнале";Outcome.FAILED->error?:"Проверку можно запустить повторно";else->null}
                message?.let{Text(it,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)}
            }
        }
    }
}

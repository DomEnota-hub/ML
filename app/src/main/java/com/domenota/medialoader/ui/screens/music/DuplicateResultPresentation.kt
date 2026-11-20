package com.domenota.medialoader.ui.screens.music

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.library.DuplicateSavedSummary
import java.text.DateFormat
import java.util.Date

/** Historical metadata, not a claim that deleted or replaced tracks remain available. */
@Composable
internal fun DuplicateSavedSummaryPanel(summary: DuplicateSavedSummary, onOpen: (() -> Unit)?) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().testTag("duplicate-saved-summary"),shape=RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Folder,null,tint=MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text("Сохранённый результат",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT)
                        .format(Date(summary.scannedAt)),style=MaterialTheme.typography.labelSmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.History,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                SummaryMetric(summary.groupCount.toString(),"групп",Modifier.weight(1f),true)
                SummaryMetric(summary.fileCount?.toString()?:"…","файлов",Modifier.weight(1f),false)
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text("Выбрано: ${summary.selectedCount?.toString()?:"…"}",style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(summary.selectedBytes?.let { Formatter.formatFileSize(context,it) } ?: "Объём уточняется",
                    style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.SemiBold)
            }
            Text("Данные последнего поиска. Доступность файлов уточняется при открытии.",
                style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(onOpen!=null)Button(onClick=onOpen,modifier=Modifier.fillMaxWidth().testTag("duplicate-open-saved")) {
                Text("Открыть результат")
            }
        }
    }
}

@Composable
private fun SummaryMetric(value:String,label:String,modifier:Modifier,highlight:Boolean) {
    Surface(modifier,shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(12.dp)) {
            Text(value,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,
                color=if(highlight)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(label,style=MaterialTheme.typography.labelMedium,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** UI-only progress. Caller supplies verified counts; never triggers another scan. */
@Composable
internal fun DuplicateRestorePanel(done:Int,total:Int,checkingFiles:Boolean,onClose:()->Unit) {
    val hasTotal=total>0
    val checked=done.coerceIn(0,total.coerceAtLeast(0))
    val percent=if(hasTotal) ((checked.toDouble()*100.0/total)+0.5).toInt() else null
    Card(Modifier.fillMaxWidth().testTag("duplicate-restore-panel"),
        shape=RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Surface(shape=RoundedCornerShape(14.dp),
                    color=MaterialTheme.colorScheme.primary.copy(alpha=.15f)) {
                    Icon(Icons.Rounded.Refresh,null,Modifier.padding(12.dp).size(26.dp),
                        tint=MaterialTheme.colorScheme.primary)
                }
                Column {
                    Text("Восстанавливаю результаты",style=MaterialTheme.typography.titleMedium,
                        fontWeight=FontWeight.SemiBold)
                    Text(if(checkingFiles)"Проверяю доступность файлов" else "Читаю сохранённые группы",
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,
                verticalAlignment=Alignment.Bottom) {
                Column {
                    Text("Проверено файлов",style=MaterialTheme.typography.labelSmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if(hasTotal)"$checked из $total" else "Подготовка данных",
                        style=MaterialTheme.typography.titleLarge)
                }
                if(percent!=null)Text("$percent%",style=MaterialTheme.typography.titleLarge,
                    fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
            }
            if(hasTotal)LinearProgressIndicator(progress={checked.toFloat()/total},
                modifier=Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)
                    .testTag("duplicate-restore-progress"))
            else LinearProgressIndicator(modifier=Modifier.fillMaxWidth().height(8.dp)
                .clip(CircleShape).testTag("duplicate-restore-progress"))
            RestoreStage("Сведения о поиске загружены",true,false)
            RestoreStage(if(checkingFiles)"Проверка файлов" else "Чтение сохранённых групп",false,true)
            RestoreStage("Подготовка списка",false,false)
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Shield,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(18.dp))
                Text("Без повторного анализа звука",style=MaterialTheme.typography.labelSmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick=onClose,modifier=Modifier.fillMaxWidth()) { Text("Закрыть") }
        }
    }
}

@Composable
private fun RestoreStage(text:String,complete:Boolean,active:Boolean) {
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {
        Icon(if(complete)Icons.Rounded.CheckCircle else if(active)Icons.Rounded.Refresh else Icons.Rounded.Circle,
            null,modifier=Modifier.size(18.dp),
            tint=if(complete)MaterialTheme.colorScheme.tertiary
                else if(active)MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.55f))
        Text(text,style=MaterialTheme.typography.bodySmall,
            color=if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Noninteractive loading skeleton. The real group list replaces it on verified restore. */
@Composable
internal fun DuplicateGroupSkeletons() {
    Column(Modifier.fillMaxWidth().testTag("duplicate-group-skeletons"),
        verticalArrangement=Arrangement.spacedBy(8.dp)) {
        repeat(2) {
            Surface(shape=RoundedCornerShape(14.dp),
                color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.5f)) {
                Row(Modifier.fillMaxWidth().height(60.dp).padding(12.dp),
                    verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(9.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha=.1f)))
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.width(132.dp).height(9.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha=.13f)))
                        Box(Modifier.width(88.dp).height(7.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha=.09f)))
                    }
                }
            }
        }
    }
}

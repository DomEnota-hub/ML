package com.domenota.medialoader.ui.components

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.library.DuplicateScanPhase
import com.domenota.medialoader.data.library.DuplicateProgressPolicy
import kotlinx.coroutines.delay

internal data class DuplicateJournalRow(val at:Long,val groups:Int,val files:Int,val status:String="Завершён",val mode:String?=null)

@Composable
internal fun DuplicateScanPanel(phase:DuplicateScanPhase,done:Int,total:Int,startedAt:Long,phaseStartedAt:Long,
    music:Boolean,groups:Int,bytes:Long,modifier:Modifier=Modifier,showSummary:Boolean=true){
    val context=androidx.compose.ui.platform.LocalContext.current
    var now by remember{mutableLongStateOf(SystemClock.elapsedRealtime())}
    LaunchedEffect(startedAt){while(true){now=SystemClock.elapsedRealtime();delay(1000)}}
    val fraction=DuplicateProgressPolicy.fraction(phase,done,total,music)
    val elapsed=(now-startedAt).coerceAtLeast(0)
    val eta=DuplicateProgressPolicy.etaMs(done,total,(now-phaseStartedAt).coerceAtLeast(0))
    val phases=if(music)listOf(DuplicateScanPhase.METADATA,DuplicateScanPhase.ACOUSTIC,DuplicateScanPhase.GROUPING,DuplicateScanPhase.SAVING)
        else listOf(DuplicateScanPhase.VISUAL,DuplicateScanPhase.GROUPING,DuplicateScanPhase.SAVING)
    val index=phases.indexOf(phase).coerceAtLeast(0)
    fun duration(ms:Long):String{val seconds=ms/1000;return "%d:%02d:%02d".format(seconds/3600,seconds/60%60,seconds%60)}
    Card(modifier.fillMaxWidth()){
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){
                Box(Modifier.size(88.dp),contentAlignment=Alignment.Center){
                    if(fraction==null)CircularProgressIndicator(Modifier.fillMaxSize())
                    else CircularProgressIndicator(progress={fraction},modifier=Modifier.fillMaxSize())
                    Text("${index+1}/${phases.size}",style=MaterialTheme.typography.titleMedium)
                }
                Column(Modifier.weight(1f)){
                    Text(phase.caption,style=MaterialTheme.typography.titleMedium)
                    if(total>0)Text("Проверено: $done из $total")
                    Text("Прошло: ${duration(elapsed)}",style=MaterialTheme.typography.bodySmall)
                    if(eta!=null)Text("До конца этапа ≈ ${duration(eta)}",style=MaterialTheme.typography.bodySmall)
                }
            }
            phases.forEachIndexed{i,p->Text((if(i<index)"✓ " else if(i==index)"● " else "○ ")+p.caption,
                color=if(i==index)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)}
            if(showSummary)Text("Найдено групп: $groups · точные копии: ${android.text.format.Formatter.formatFileSize(context,bytes)}",
                style=MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun DuplicateJournalDialog(title:String,rows:List<DuplicateJournalRow>,partial:DuplicateJournalRow?,
    onOpen:(Long)->Unit,onResume:()->Unit,onDismiss:()->Unit,onPartial:(()->Unit)?=null,resumeEnabled:Boolean=true,
    onClear:(()->Unit)?=null,clearEnabled:Boolean=true){
    AlertDialog(onDismissRequest=onDismiss,title={Row(verticalAlignment=Alignment.CenterVertically){
        Text(title,Modifier.weight(1f))
        if(onClear!=null)OutlinedIconButton(onClear,modifier=Modifier.size(44.dp),
            enabled=clearEnabled&&(rows.isNotEmpty()||partial!=null)){
            Icon(androidx.compose.material.icons.Icons.Rounded.DeleteOutline,"Очистить журнал")
        }
    }},text={
        LazyColumn(Modifier.heightIn(max=420.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            if(partial!=null)item{
                Card{Column(Modifier.padding(12.dp)){
                    Text(partial.status,style=MaterialTheme.typography.titleMedium)
                    partial.mode?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                    Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(partial.at)))
                    Text("Групп: ${partial.groups} · файлов: ${partial.files}")
                    Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
                        TextButton(onResume,enabled=resumeEnabled){Text("Продолжить")}
                        onPartial?.let{TextButton(it){Text("Результаты")}}
                    }
                }}
            }
            if(rows.isEmpty()&&partial==null)item{
                Column(Modifier.fillMaxWidth().padding(vertical=22.dp),
                    horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(9.dp)) {
                    Icon(androidx.compose.material.icons.Icons.Rounded.History,null,
                        tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(38.dp))
                    Text("Журнал пока пуст",style=MaterialTheme.typography.titleMedium)
                    Text("Сохранённые проверки появятся здесь",
                        style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(rows,key={it.at}){row->
                Card(onClick={onOpen(row.at)}){Column(Modifier.padding(12.dp)){
                    Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(row.at)),style=MaterialTheme.typography.titleMedium)
                    row.mode?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                    Text("${row.status} · групп: ${row.groups} · файлов: ${row.files}",style=MaterialTheme.typography.bodySmall)
                }}
            }
        }
    },confirmButton={TextButton(onDismiss){Text("Закрыть")}})
}

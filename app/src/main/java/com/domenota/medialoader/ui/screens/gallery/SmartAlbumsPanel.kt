package com.domenota.medialoader.ui.screens.gallery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.domenota.medialoader.data.library.*

@Composable
internal fun SmartAlbumsPanel(settings:SmartAlbumSettings,status:SmartIndexStatus,onSettings:()->Unit,
    onRefresh:()->Unit,onOpen:(SmartAlbum)->Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal=17.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("Умные альбомы",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            IconButton(onRefresh,enabled=!status.running && settings.enabled.isNotEmpty()) { Icon(Icons.Rounded.Refresh,"Обновить анализ") }
            IconButton(onSettings) { Icon(Icons.Rounded.Settings,"Настроить умные альбомы") }
        }
        if(status.running) {
            LinearProgressIndicator(progress={ if(status.total==0) 0f else status.checked.toFloat()/status.total },modifier=Modifier.fillMaxWidth())
            Text("Анализ фотографий · ${status.checked}/${status.total}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(status.failed>0) Text("Не удалось проверить: ${status.failed}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        status.error?.let { Text(it,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        if(settings.enabled.isEmpty()) Text("Категории отключены",color=MaterialTheme.colorScheme.onSurfaceVariant)
        else Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainer) {
            Column {
                SmartAlbum.entries.filter { it in settings.enabled }.forEachIndexed { i,category ->
                    if(i>0) HorizontalDivider(Modifier.padding(horizontal=14.dp))
                    Surface(onClick={onOpen(category)},color=MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,
                            horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Icon(when(category) {
                                SmartAlbum.DOCUMENTS->Icons.Rounded.Description
                                SmartAlbum.PORTRAITS->Icons.Rounded.Portrait
                                SmartAlbum.SELFIES->Icons.Rounded.Face
                                SmartAlbum.HIGH_RESOLUTION->Icons.Rounded.HighQuality
                            },null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(category.title,Modifier.weight(1f))
                            Text((status.members[category]?.size ?: 0).toString(),color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Icon(Icons.Rounded.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SmartAlbumOptionsDialog(settings:SmartAlbumSettings,onDismiss:()->Unit,onSave:(SmartAlbumSettings)->Unit) {
    var enabled by remember { mutableStateOf(settings.enabled) }
    var threshold by remember { mutableIntStateOf(settings.threshold) }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Умные альбомы")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            SmartAlbum.entries.forEach { category ->
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(category in enabled,{ checked -> enabled=if(checked) enabled+category else enabled-category })
                    Text(category.title)
                }
            }
            Text("Высокое разрешение: от $threshold Мп")
            Slider(threshold.toFloat(),{threshold=it.toInt()},valueRange=8f..108f,steps=99)
            Text("Документы, портреты и селфи определяются приблизительно, локально на устройстве. Файлы не перемещаются.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    },confirmButton={TextButton({onSave(SmartAlbumSettings(enabled,threshold))}){Text("Сохранить")}},
    dismissButton={TextButton(onDismiss){Text("Отмена")}})
}

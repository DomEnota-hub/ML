package com.domenota.medialoader.ui.screens.gallery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.library.*

@Composable
internal fun SmartAlbumsSection(photos: Map<String,SmartPhoto>,status: SmartAlbumStatus,visible: Set<String>,
    threshold: Int,onOpen: (SmartCategory)->Unit,onSettings: ()->Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 17.dp,vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Умные альбомы",Modifier.weight(1f),style = MaterialTheme.typography.titleMedium)
            IconButton(onSettings) { Icon(Icons.Rounded.Settings,"Настроить умные альбомы") }
        }
        if (status.running) {
            LinearProgressIndicator(progress = { if (status.total > 0) status.done.toFloat()/status.total else 0f },modifier = Modifier.fillMaxWidth())
            Text("Анализ: ${status.done}/${status.total}",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(shape = RoundedCornerShape(16.dp),color = MaterialTheme.colorScheme.surfaceContainer) {
            Column {
                val categories = SmartCategory.entries.filter { it.name in visible }
                categories.forEachIndexed { index, category ->
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                    val count = photos.values.count { it.matches(category,threshold) }
                    Surface(onClick = { onOpen(category) },color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment = Alignment.CenterVertically) {
                            Icon(when(category) {
                                SmartCategory.DOCUMENTS -> Icons.Rounded.Description
                                SmartCategory.PORTRAITS -> Icons.Rounded.Face
                                SmartCategory.SELFIES -> Icons.Rounded.CameraFront
                                SmartCategory.HIGH_RES -> Icons.Rounded.HighQuality
                            },null,Modifier.size(24.dp),tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(category.title,Modifier.weight(1f).padding(start = 12.dp))
                            Text(count.toString(),color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Icon(Icons.Rounded.ChevronRight,null,Modifier.padding(start = 5.dp))
                        }
                    }
                }
                if (categories.isEmpty()) Text("Категории скрыты",Modifier.padding(14.dp),color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (status.skipped > 0) Text("Не удалось проверить: ${status.skipped}",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SmartAlbumsSettingsDialog(visible: Set<String>,threshold: Int,onVisibility: (Set<String>)->Unit,
    onThreshold: (Int)->Unit,onRefresh: ()->Unit,onDismiss: ()->Unit) {
    AlertDialog(onDismissRequest = onDismiss,title = { Text("Умные альбомы") },text = {
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmartCategory.entries.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(c.name in visible,{ checked -> onVisibility(if (checked) visible+c.name else visible-c.name) })
                    Text(c.title)
                }
            }
            Text("Высокое разрешение: $threshold Мп")
            Slider(threshold.toFloat(),{ onThreshold(it.toInt()) },valueRange = 8f..64f,steps = 55)
            Text("Документы, портреты и селфи определяются локально по признакам снимка. Селфи без метаданных камеры определяются приблизительно.",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onRefresh,enabled = visible.isNotEmpty(),modifier = Modifier.fillMaxWidth()) { Text("Обновить индекс") }
        }
    },confirmButton = { TextButton(onDismiss) { Text("Готово") } })
}

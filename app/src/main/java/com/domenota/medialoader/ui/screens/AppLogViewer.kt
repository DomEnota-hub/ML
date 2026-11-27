package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.logging.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Presentation only; existing access and export operate on the original log. */
@Composable
internal fun AppLogViewer(
    rawText: String,
    readable: Boolean,
    onReadableChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onCopy: ((String) -> Unit)? = null,
) {
    var snapshot by remember(rawText) { mutableStateOf<JournalTimeline.Snapshot?>(null) }
    LaunchedEffect(rawText) {
        snapshot = withContext(Dispatchers.Default) { JournalTimeline.prepare(rawText) }
    }
    var tone by rememberSaveable { mutableStateOf<String?>(null) }
    // Search input may contain a filename or private text; do not save it to instance state.
    var query by remember { mutableStateOf("") }
    var newest by rememberSaveable { mutableStateOf(false) }
    val selected = LogTone.entries.firstOrNull { it.name == tone }
    var rows by remember(snapshot, tone, readable, query, newest) { mutableStateOf<List<JournalTimeline.Row>?>(null) }
    LaunchedEffect(snapshot, tone, readable, query, newest) {
        val s = snapshot ?: return@LaunchedEffect
        rows = withContext(Dispatchers.Default) { s.select(selected, readable, query, newest) }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(tone, readable, query, newest) { listState.scrollToItem(0) }
    val expanded = remember(snapshot) { mutableStateMapOf<Int, Boolean>() }
    val s = snapshot
    val shown = rows
    val controls: @Composable () -> Unit = {
      Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = readable, onClick = { onReadableChange(true) },
                label = { Text("Понятный", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                modifier = Modifier.weight(1f))
            FilterChip(selected = !readable, onClick = { onReadableChange(false) },
                label = { Text("Технический", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                modifier = Modifier.weight(1f))
        }
        val options = listOf<LogTone?>(null) + LogTone.entries
        options.chunked(3).forEach { line ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                line.forEach { t ->
                    val active = selected == t
                    val color = t?.tint() ?: MaterialTheme.colorScheme.primary
                    val count = if (t == null) snapshot?.raw?.size else snapshot?.counts?.get(t) ?: snapshot?.let { 0 }
                    Surface(
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            .clip(MaterialTheme.shapes.medium).selectable(selected = active, role = Role.RadioButton) { tone = t?.name },
                        shape = MaterialTheme.shapes.medium,
                        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        border = if (active) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                    ) {
                        Column(Modifier.padding(horizontal = 3.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(count?.toString() ?: "—", color = color, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            Text(when (t) { LogTone.WARN -> "Внимание"; else -> t?.label ?: "Все" },
                                style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Поиск по журналу") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Очистить поиск") } },
                modifier = Modifier.weight(1f))
            IconButton(onClick = { newest = !newest }) {
                Icon(Icons.Rounded.Sort, if (newest) "Сначала новые. Изменить порядок" else "Сначала старые. Изменить порядок",
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
        if (s != null && shown != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Записей: " + shown.sumOf { it.count }, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (newest) "Сначала новые" else "Сначала старые", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (readable && s.raw.isNotEmpty()) {
                val perMille = s.genericCount * 1000L / s.raw.size
                Text("Общие пояснения: " + s.genericCount + " из " + s.raw.size + " · " + perMille / 10 + "," + perMille % 10 + "%",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
      }
    }
    BoxWithConstraints(modifier) {
      // Keep all six tiles available on narrow/short screens without starving the list.
      val compact = maxHeight < 340.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.25f
      Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (!compact) controls()
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
            if (compact) item(key = "controls") { controls() }
            if (s == null || shown == null) {
                item { Text("Подготовка журнала…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                val showPrefix = !readable && selected == null && s.prefix.isNotBlank() &&
                    (query.isBlank() || s.prefix.contains(query.trim(), true))
                if (showPrefix && !newest) item(key = "prefix") { OrphanLines(s.prefix) }
                if (shown.isEmpty() && !showPrefix)
                    item { Text("Нет записей по выбранным условиям.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                itemsIndexed(shown, key = { _, row -> row.start }) { index, row ->
                    val e = s.raw[row.end]
                    val date = e.timestamp.substringBefore(' ')
                    if (index == 0 || s.raw[shown[index - 1].end].timestamp.substringBefore(' ') != date) {
                        Text(date, Modifier.padding(vertical = 7.dp), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (readable) TimelineLine(s, row, expanded[row.start] == true,
                        onExpand = { expanded[row.start] = expanded[row.start] != true }, onCopy = onCopy)
                    else TechnicalLine(s.recordText(row.start), e.tone, onCopy)
                }
                if (showPrefix && newest) item(key = "prefix") { OrphanLines(s.prefix) }
            }
        }
      }
    }
}
@Composable
private fun OrphanLines(text: String) {
    Text("Строки без заголовка события", style = MaterialTheme.typography.labelSmall)
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
}
@Composable
private fun LogTone.tint(): Color {
    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
    return when (this) {
        LogTone.ERROR -> if (light) Color(0xFFB82B41) else Color(0xFFF47784)
        LogTone.WARN -> if (light) Color(0xFF986000) else Color(0xFFFFAB40)
        LogTone.OK -> if (light) Color(0xFF177346) else Color(0xFF66D5A2)
        LogTone.INFO -> if (light) Color(0xFF216BC0) else Color(0xFF479DF5)
        LogTone.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
private fun eventIcon(entry: DisplayLogEntry): ImageVector = when (entry.tag) {
    "Download", "DownloadProgress", "Resolve", "Share" -> Icons.Rounded.Download
    "Thumbnail", "Gallery", "GallerySearch", "PhotoCutout" -> Icons.Rounded.Image
    "OCR", "PhotoOCR" -> Icons.Rounded.TextFields
    "AudioWaveform", "MusicCompare" -> Icons.Rounded.GraphicEq
    "TaskCenter" -> when (entry.metadata.split(" · ").filter { it.startsWith("kind=") }.singleOrNull()?.substringAfter('=')) {
        "WAVEFORM", "LOUDNESS" -> Icons.Rounded.GraphicEq
        "OCR" -> Icons.Rounded.TextFields
        "DOWNLOAD", "BULK", "PROFILE", "COMMUNITY" -> Icons.Rounded.Download
        "VISUAL_DUPLICATES", "AUDIO_DUPLICATES" -> Icons.Rounded.ContentCopy
        "MODEL" -> Icons.Rounded.Memory
        "EXPORT", "BATCH_EDIT" -> Icons.Rounded.Edit
        "FILE_OPERATION" -> Icons.Rounded.Folder
        else -> Icons.Rounded.ListAlt
    }
    "Library" -> Icons.Rounded.Folder
    "Perf" -> Icons.Rounded.Speed
    "ProcessExit", "Crash", "App" -> Icons.Rounded.Smartphone
    "FolderVisibility", "NOMEDIA_SAF", "OldDownloads", "MissingRecovery", "History" -> Icons.Rounded.Archive
    "Trash" -> Icons.Rounded.DeleteOutline
    "MediaNotes" -> Icons.Rounded.Notes
    "Duplicate", "DuplicateUI", "DuplicateScanMetrics", "MusicDuplicates", "MUSIC_REPORT_COMMIT" -> Icons.Rounded.ContentCopy
    "DuplicatePrivacy" -> Icons.Rounded.Security
    "VideoPlayer", "FloatingVideo", "VideoRecognition", "VideoEditorNav", "VideoPreview", "Viewer" -> Icons.Rounded.VideoLibrary
    "AiOrtPerf" -> Icons.Rounded.Memory
    "Diagnostics" -> Icons.Rounded.BugReport
    "GapiUsage" -> Icons.Rounded.Wifi
    "MusicPreview", "RemotePreview", "Listening" -> Icons.Rounded.MusicNote
    "PreviewFrame", "AudioPreview", "AudioSync" -> Icons.Rounded.VideoLibrary
    "Editor" -> Icons.Rounded.Edit
    "Settings" -> Icons.Rounded.Settings
    "Logs" -> Icons.Rounded.Article
    "Browser", "BrowserWebView", "VKAuth", "RutubeAuth", "YouTubeAuth", "InstagramLogin", "InstagramSession", "VK", "RUTUBE", "YouTube", "Instagram", "InstagramProfile", "VkCommunity", "VkFavoriteCheck" -> Icons.Rounded.Language
    "Muzmo", "HitMoz", "MusicSource", "MusicWebView", "Recognition", "MusicArtwork", "MusicAds", "Audio", "AudioRoute", "AudioReplace" -> Icons.Rounded.MusicNote
    else -> Icons.Rounded.Info
}

@Composable
private fun TimelineLine(snapshot: JournalTimeline.Snapshot, row: JournalTimeline.Row, expanded: Boolean,
    onExpand: () -> Unit, onCopy: ((String) -> Unit)?) {
    val entry = snapshot.raw[row.end]
    val detail = snapshot.details[row.end]
    val segments = remember(detail) { detail.meaning.split(" · ", limit = 2) }
    val tint = entry.tone.tint()
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().drawBehind {
        val x = 17.dp.toPx()
        drawLine(line, Offset(x, 32.dp.toPx()), Offset(x, size.height), 1.dp.toPx())
    }.padding(bottom = 9.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(34.dp).background(tint.copy(alpha = .14f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center) { Icon(eventIcon(entry), null, tint = tint, modifier = Modifier.size(20.dp)) }
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val time = if (row.count > 1) snapshot.raw[row.start].timestamp.substringAfter(' ').substringBefore('.') +
                    "–" + entry.timestamp.substringAfter(' ').substringBefore('.') else entry.timestamp.substringAfter(' ').substringBefore('.')
                Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(LogDisplay.groupName(entry), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
            }
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onExpand).padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(segments.first(), Modifier.weight(1f), color = tint,
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        if (expanded) "Свернуть подробности" else "Раскрыть подробности",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                segments.getOrNull(1)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                JournalTimeline.progress(entry)?.let { p ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.weight(1f).height(4.dp),
                            color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.outlineVariant)
                        Text((p.fraction * 100).toInt().toString() + "%", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (row.count > 1) Text(row.count.toString() + " связанных записей · показана последняя", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (expanded) {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                    .padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    detail.advice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text("Исходные записи", style = MaterialTheme.typography.labelMedium)
                    // Page the expansion so no raw record is inaccessible, even in a huge group.
                    var page by remember(row.start, row.end) { mutableIntStateOf(0) }
                    val start = row.start + page * 20
                    val end = minOf(row.end, start + 19)
                    if (row.count > 20) Text((start - row.start + 1).toString() + "–" +
                        (end - row.start + 1) + " из " + row.count, style = MaterialTheme.typography.labelSmall)
                    for (i in start..end) TechnicalLine(snapshot.recordText(i), snapshot.raw[i].tone, onCopy)
                    if (row.count > 20) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { page-- }, enabled = page > 0) { Text("Назад") }
                        TextButton(onClick = { page++ }, enabled = end < row.end) { Text("Далее") }
                    }
                }
            }
        }
    }
}
@Composable
private fun TechnicalLine(raw: String, tone: LogTone, onCopy: ((String) -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(3.dp).height(24.dp).background(tone.tint(), RoundedCornerShape(2.dp)))
        Text(raw, Modifier.weight(1f).padding(start = 6.dp), fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        if (onCopy != null) IconButton(onClick = { onCopy(raw) }) {
            Icon(Icons.Rounded.ContentCopy, "Копировать запись", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable
internal fun JournalFooter(onRefresh: () -> Unit, onClear: () -> Unit, onExport: () -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.large),
        horizontalArrangement = Arrangement.SpaceEvenly) {
        val items = listOf(
            Triple("Обновить", Icons.Rounded.Refresh, onRefresh),
            Triple("Очистить", Icons.Rounded.DeleteOutline, onClear),
            Triple("Экспорт", Icons.Rounded.FileDownload, onExport),
            Triple("Закрыть", Icons.Rounded.Close, onClose),
        )
        items.forEachIndexed { i, (label, icon, action) ->
            val color = when (i) {
                1 -> MaterialTheme.colorScheme.error
                3 -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.primary
            }
            Column(Modifier.weight(1f).heightIn(min = 48.dp).clip(MaterialTheme.shapes.medium)
                .clickable(role = Role.Button, onClick = action).padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Icon(icon, null, tint = color)
                Text(label, color = color, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            }
        }
    }
}

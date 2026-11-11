package com.domenota.medialoader.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ListAlt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.data.bulk.BulkLinks
import com.domenota.medialoader.data.bulk.BulkOutcomeCounts
import com.domenota.medialoader.data.bulk.BulkPacing
import com.domenota.medialoader.data.bulk.BulkPhase
import com.domenota.medialoader.data.bulk.BulkState
import kotlinx.coroutines.delay

private val bulkPurple = Color(0xFF8456EC)

/** The persisted bulk StateFlow is the only source of progress; no synthetic progress values. */
@Composable
fun BulkProgressCard(
    state: BulkState,
    downloads: List<DownloadEntity>,
    onDetails: () -> Unit,
    onStopAnalysis: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDismissFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.phase == BulkPhase.EMPTY || state.jobId.isBlank()) return
    var expanded by rememberSaveable(state.jobId) { mutableStateOf(true) }
    var now by remember(state.jobId) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.phase, state.lastInstagramAttemptAt, state.rules.disableAnalysisPause) {
        while (state.phase == BulkPhase.ANALYZING) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val linkedIds = remember(state.candidates) { state.candidates.mapNotNull { it.downloadId }.toSet() }
    val related = remember(linkedIds, downloads) { downloads.filter { it.id in linkedIds } }
    val completed = related.count { it.state == DownloadState.COMPLETED }
    val failed = related.count { it.state == DownloadState.FAILED }
    val cancelled = related.count { it.state == DownloadState.CANCELLED }
    val counts = BulkOutcomeCounts.from(
        selected = originalSelected, queued = state.queuedCount,
        completed = completed, failed = failed, cancelled = cancelled,
    )
    val isAnalysis = state.phase == BulkPhase.ANALYZING || state.phase == BulkPhase.PREVIEW
    val originalSelected = if (state.plannedCount > 0) state.plannedCount
        else maxOf(state.selectedCount, state.queuedCount)
    val total = if (isAnalysis) state.urls.size else originalSelected
    val processed = if (isAnalysis) state.cursor.coerceAtMost(total)
        else counts.terminal.coerceAtMost(total)
    val percent = if (total > 0) processed * 100 / total else 0
    val finished = state.phase in setOf(BulkPhase.PREVIEW, BulkPhase.FINISHED, BulkPhase.STOPPED)
    val title = when (state.phase) {
        BulkPhase.ANALYZING -> "Массовый анализ"
        BulkPhase.PREVIEW -> "Анализ завершён"
        BulkPhase.QUEUING -> "Массовая загрузка"
        BulkPhase.PAUSED -> "Загрузка приостановлена"
        BulkPhase.FINISHED -> "Загрузка завершена"
        BulkPhase.STOPPED -> "Задание остановлено"
        BulkPhase.EMPTY -> return
    }
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), tonalElevation = 2.dp) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (finished) Icons.Rounded.CheckCircle else Icons.Rounded.ListAlt,
                    contentDescription = null, tint = if (finished) Color(0xFF35B779) else bulkPurple)
                Text(title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (!expanded) Text("$processed/$total", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall)
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Свернуть" else "Развернуть",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                if (!finished) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("$processed из $total", style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold)
                            Text(if (isAnalysis) "Ссылок обработано" else "Файлов обработано",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("$percent%", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold)
                    }
                    Box(Modifier.fillMaxWidth().height(7.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth(percent / 100f).height(7.dp)
                            .background(bulkPurple, RoundedCornerShape(8.dp)))
                    }
                } else {
                    Text(if (state.phase == BulkPhase.PREVIEW)
                            "${state.urls.size} ссылок обработано"
                        else "Выбрано: ${counts.selected}, в очереди: ${counts.queued}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric(if (isAnalysis) state.candidates.size else completed,
                        if (isAnalysis) "Найдено файлов" else "Скачано")
                    Metric(if (isAnalysis) state.errors.size else failed, "Ошибки")
                    Metric(if (isAnalysis) state.unsupported + state.duplicates else cancelled,
                        if (isAnalysis) "Пропуски/повторы" else "Отменено")
                }
                if (!isAnalysis && (counts.neverQueued > 0 ||
                        counts.pendingOrUnknown > 0)) {
                    Text(
                        buildString {
                            if (counts.neverQueued > 0) {
                                append("Не поставлено в очередь: ${counts.neverQueued}. ")
                            }
                            if (counts.pendingOrUnknown > 0) {
                                append("Без подтверждённого итога: ${counts.pendingOrUnknown}. ")
                            }
                            append("Подробнее в журнале задания.")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.phase == BulkPhase.ANALYZING) {
                    val nextUrl = state.urls.getOrNull(state.cursor)
                    val provider = nextUrl?.let(BulkLinks::provider).orEmpty()
                    val remaining = if (provider == "instagram") BulkPacing.waitMillis(
                        state.lastInstagramAttemptAt, now, state.rules.disableAnalysisPause) else 0L
                    val stage = when {
                        remaining > 0 -> "Instagram · ожидание перед запросом: ${(remaining + 999) / 1000} с"
                        provider.isNotEmpty() -> "Анализ · ${provider.replaceFirstChar { it.uppercase() }}"
                        else -> "Подготовка результатов"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(Icons.Rounded.Schedule, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Text(stage, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    if (state.phase == BulkPhase.ANALYZING)
                        NeutralTextButton(onStopAnalysis) { Text("Остановить") }
                    if (state.phase == BulkPhase.QUEUING)
                        NeutralTextButton(onPause) { Text("Пауза") }
                    if (state.phase == BulkPhase.PAUSED)
                        NeutralTextButton(onResume) { Text("Продолжить") }
                    if (finished)
                        NeutralTextButton(onDismissFinished) { Text("Скрыть") }
                    NeutralTextButton(onDetails) {
                        Text(if (state.phase == BulkPhase.PREVIEW) "Просмотреть файлы" else "Подробнее")
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null,
                            modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Metric(count: Int, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$count", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

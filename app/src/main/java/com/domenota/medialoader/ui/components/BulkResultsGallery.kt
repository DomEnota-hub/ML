package com.domenota.medialoader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.bulk.BulkCandidate
import com.domenota.medialoader.data.bulk.BulkState
import com.domenota.medialoader.ui.model.MediaKind

/**
 * Render downloaded bulk-job results with shared thumbnail rendering and viewer routing.
 * The source URL is the grouping key, never the provider alone.
 */
@Composable
fun BulkResultsGallery(
    state: BulkState,
    downloads: List<DownloadEntity>,
    onOpenGroupMedia: (String, List<String>) -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("bulk_rules", 0) }
    var grid by rememberSaveable { mutableStateOf(preferences.getBoolean("results_grid", true)) }
    val byId = remember(downloads) { downloads.associateBy { it.id } }
    val grouped = remember(state.urls, state.candidates) {
        state.candidates.filter { it.downloadId != null }.groupBy { candidate ->
            val index = candidate.key.substringBefore(':').toIntOrNull()
            state.urls.getOrNull(index ?: -1)?.takeIf { it.isNotBlank() }
                ?: candidate.item.sourcePageUrl?.takeIf { it.isNotBlank() }
                ?: candidate.item.sourceGroupId?.takeIf { it.isNotBlank() }
                ?: candidate.key
        }
    }
    if (grouped.isEmpty()) return

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Результаты загрузки", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold)
        Row {
            IconButton(onClick = {
                grid = false
                preferences.edit().putBoolean("results_grid", false).apply()
            }) {
                Icon(Icons.AutoMirrored.Rounded.ViewList, "Список",
                    tint = if (!grid) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = {
                grid = true
                preferences.edit().putBoolean("results_grid", true).apply()
            }) {
                Icon(Icons.Rounded.GridView, "Сетка",
                    tint = if (grid) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    grouped.entries.forEachIndexed { index, (_, candidates) ->
        val rows = candidates.mapNotNull { candidate ->
            candidate.downloadId?.let(byId::get)?.let { candidate to it }
        }
        val groupQueue = rows.map { it.second }.filter {
            it.state == DownloadState.COMPLETED && !it.savedUri.isNullOrBlank() &&
                !it.hidden && !it.isPrivate && it.mediaType != MediaType.AUDIO
        }.map { it.id }
        val label = when (candidates.first().item.providerId.lowercase()) {
            "instagram" -> "Instagram"
            "youtube" -> "YouTube"
            "vk" -> "VK"
            "rutube" -> "RUTUBE"
            else -> "Источник"
        }
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Публикация ${index + 1} · $label",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                Text("${rows.size} файлов", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (grid) {
                var visible by rememberSaveable(state.jobId, index) { mutableIntStateOf(24) }
                rows.take(visible).chunked(3).forEach { chunk ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        chunk.forEach { (candidate, row) ->
                            Box(Modifier.weight(1f)) {
                                BulkResultGridItem(candidate, row) {
                                    val queue = if (row.mediaType == MediaType.AUDIO) listOf(row.id)
                                        else groupQueue
                                    onOpenGroupMedia(row.id, queue)
                                }
                            }
                        }
                        repeat(3 - chunk.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (rows.size > visible) {
                    NeutralTextButton(onClick = { visible += 24 }) {
                        Text("Показать ещё ${minOf(24, rows.size - visible)}")
                    }
                }
            } else {
                var visible by rememberSaveable(state.jobId, index) { mutableIntStateOf(40) }
                rows.take(visible).forEach { (candidate, row) ->
                    BulkResultListItem(candidate, row) {
                        val queue = if (row.mediaType == MediaType.AUDIO) listOf(row.id) else groupQueue
                        onOpenGroupMedia(row.id, queue)
                    }
                }
                if (rows.size > visible) {
                    NeutralTextButton(onClick = { visible += 40 }) {
                        Text("Показать ещё ${minOf(40, rows.size - visible)}")
                    }
                }
            }
        }
    }
}

private fun canOpen(row: DownloadEntity): Boolean =
    row.state == DownloadState.COMPLETED && !row.savedUri.isNullOrBlank() && !row.hidden && !row.isPrivate

private fun MediaType.asGalleryKind(): MediaKind = when (this) {
    MediaType.PHOTO -> MediaKind.IMAGE
    MediaType.VIDEO -> MediaKind.VIDEO
    MediaType.AUDIO -> MediaKind.AUDIO
}

private fun resultStatus(row: DownloadEntity): String = when (row.state) {
    DownloadState.COMPLETED -> "Скачано"
    DownloadState.RUNNING -> "Скачивается"
    DownloadState.QUEUED -> "В очереди"
    DownloadState.FAILED -> "Ошибка"
    DownloadState.CANCELLED -> "Отменено"
}

@Composable
private fun BulkResultGridItem(candidate: BulkCandidate, row: DownloadEntity, onOpen: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .clickable(enabled = canOpen(row), onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(98.dp).clip(RoundedCornerShape(12.dp))) {
                DownloadThumbnail(row.savedUri, candidate.item.previewUrl, row.mediaType.asGalleryKind(),
                    Modifier.fillMaxWidth().height(98.dp), progressive = true)
            }
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(row.originalName, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall)
                Text(resultStatus(row), maxLines = 1,
                    color = if (row.state == DownloadState.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun BulkResultListItem(candidate: BulkCandidate, row: DownloadEntity, onOpen: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .clickable(enabled = canOpen(row), onClick = onOpen),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
        shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Box(Modifier.size(68.dp).clip(RoundedCornerShape(11.dp))) {
                DownloadThumbnail(row.savedUri, candidate.item.previewUrl, row.mediaType.asGalleryKind(),
                    Modifier.size(68.dp), progressive = true)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.originalName, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(resultStatus(row), style = MaterialTheme.typography.bodySmall,
                    color = if (row.state == DownloadState.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

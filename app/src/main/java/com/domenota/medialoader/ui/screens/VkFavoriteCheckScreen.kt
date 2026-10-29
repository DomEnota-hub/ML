package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.domenota.medialoader.ui.components.NeutralOutlinedButton as OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.vkcommunity.VkCommunityJob
import com.domenota.medialoader.data.vkcommunity.VkFavoriteCheckBatch
import com.domenota.medialoader.data.vkcommunity.VkFavoriteCheckEntry
import com.domenota.medialoader.data.vkcommunity.VkFavorite
import com.domenota.medialoader.data.vkcommunity.VkJobPhase

@Composable
fun VkFavoriteCheckScreen(batch: VkFavoriteCheckBatch?, jobs: Map<String, VkCommunityJob>,
    favorites: List<VkFavorite>,
    onBack: () -> Unit, onOpen: (VkFavoriteCheckEntry) -> Unit,
    onRetry: () -> Unit, onNewCheck: () -> Unit) {
    val entries = batch?.entries.orEmpty()
    val currentFavorites = favorites.associateBy { it.key }
    fun stale(entry: VkFavoriteCheckEntry): Boolean {
        val favorite = currentFavorites[entry.favoriteKey]
        return !entry.reviewed && (favorite == null || favorite.sinceDate != entry.sinceDate ||
            favorite.reviewedThroughEpoch != entry.reviewedThroughAtStart)
    }
    val running = entries.any { jobs[it.jobId]?.phase == VkJobPhase.SCANNING }
    val pendingReview = entries.any { entry ->
        !entry.reviewed && !stale(entry) && jobs[entry.jobId]?.scanComplete == true
    }
    val failed = entries.any { entry ->
        !entry.reviewed && jobs[entry.jobId]?.let {
            !it.scanComplete && it.phase in setOf(VkJobPhase.ERROR, VkJobPhase.CANCELLED)
        } == true
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "К сообществам") }
            Text("Новые публикации VK", style = MaterialTheme.typography.titleLarge)
        }
        Text("Изображения из избранных сообществ. Скачивание начнётся только после вашего выбора.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (entries.isEmpty()) Text("Проверок пока нет")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(entries, key = { it.jobId }) { entry ->
                val job = jobs[entry.jobId]
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(entry.label, style = MaterialTheme.typography.titleMedium)
                        val status = when {
                            stale(entry) -> "Дата изменена · нужна новая проверка"
                            entry.reviewed && (job?.found ?: 0) == 0 -> "Новых изображений нет"
                            entry.reviewed -> "Просмотрено · найдено ${job?.found ?: 0}"
                            job == null -> "Результаты удалены · нужна новая проверка"
                            job.phase == VkJobPhase.SCANNING -> "Проверяется…"
                            !job.scanComplete -> "Не удалось проверить: ${job.error ?: "ошибка"}"
                            else -> "Найдено ${job.found} изображений · выбрано ${job.selected}"
                        }
                        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (job?.phase == VkJobPhase.SCANNING)
                            CircularProgressIndicator(Modifier.padding(top = 8.dp))
                        if (!stale(entry) && job?.scanComplete == true) {
                            OutlinedButton(onClick = { onOpen(entry) },
                                modifier = Modifier.padding(top = 8.dp)) {
                                Text(if (entry.reviewed) "Открыть результаты" else "Просмотреть и выбрать")
                            }
                        }
                    }
                }
            }
        }
        if (pendingReview) Text("Завершите просмотр найденных изображений перед новой проверкой.",
            style = MaterialTheme.typography.bodySmall)
        if (failed && !running) OutlinedButton(onClick = onRetry,
            modifier = Modifier.fillMaxWidth()) { Text("Повторить ошибки") }
        Button(onClick = onNewCheck, enabled = !running && !pendingReview,
            modifier = Modifier.fillMaxWidth()) { Text("Проверить снова") }
        Spacer(Modifier.height(8.dp))
    }
}

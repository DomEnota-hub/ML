package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.vkcommunity.VkCommunityJob
import com.domenota.medialoader.data.vkcommunity.VkHistoryPolicy
import java.text.DateFormat
import java.util.Date

@Composable
fun VkCommunityHistoryScreen(
    jobs: List<VkCommunityJob>, onBack: () -> Unit, onOpen: (VkCommunityJob) -> Unit,
    onRepeat: (VkCommunityJob) -> Unit, onDelete: (VkCommunityJob) -> Unit,
    onClear: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Назад") }
            Text("Журнал поиска VK", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f))
        }
        if (jobs.any { VkHistoryPolicy.canDelete(it.phase) })
            TextButton(onClick = onClear) { Text("Очистить журнал") }
        if (jobs.isEmpty()) Text("Поисков пока не было")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(jobs, key = { it.id }) { job ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(job.link, style = MaterialTheme.typography.titleMedium)
                        Text("${job.startDate} — ${job.endDate} · " +
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(job.createdAt)))
                        Text("${job.phase.name.lowercase()} · найдено ${job.found} · " +
                            "скачано ${job.downloaded} · ошибок ${job.failed}")
                        Row {
                            TextButton(onClick = { onOpen(job) }) { Text("Результаты") }
                            TextButton(onClick = { onRepeat(job) }) { Text("Повторить поиск") }
                            if (VkHistoryPolicy.canDelete(job.phase))
                                TextButton(onClick = { onDelete(job) }) { Text("Удалить") }
                        }
                    }
                }
            }
        }
    }
}

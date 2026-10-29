package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.domenota.medialoader.ui.components.NeutralOutlinedButton as OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.vkcommunity.VkAdStatus
import com.domenota.medialoader.data.vkcommunity.VkCommunityDatabase
import com.domenota.medialoader.data.vkcommunity.VkCommunityJob
import com.domenota.medialoader.data.vkcommunity.VkItemPhase
import com.domenota.medialoader.data.vkcommunity.VkJobPhase
import com.domenota.medialoader.data.vkcommunity.VkResultPosition
import com.domenota.medialoader.data.vkcommunity.VkStoredImage
import com.domenota.medialoader.ui.components.RemotePreview
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun VkCommunityResultsScreen(
    job: VkCommunityJob?, images: List<VkStoredImage>, page: Int, hasPendingSelection: Boolean,
    readOnly: Boolean, database: VkCommunityDatabase, onBack: () -> Unit, onPage: (Int) -> Unit,
    onSelect: (VkStoredImage, Boolean) -> Unit, onSelectPost: (VkStoredImage, Boolean) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onDownload: () -> Unit, onRetry: () -> Unit, onCancel: () -> Unit,
    onContinue: () -> Unit, onRepeat: () -> Unit,
    onFinishReview: (() -> Unit)? = null,
    isFavoriteCheck: Boolean = false,
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("vk_community_ui", 0) }
    var grid by remember { mutableStateOf(prefs.getBoolean("results_grid", true)) }
    var previewIndex by remember(job?.id) { mutableIntStateOf(-1) }
    if (previewIndex >= 0 && job != null) {
        VkCommunityImageViewer(job.id, previewIndex, job.found, readOnly, database,
            onSelect = onSelect, onSelectPost = onSelectPost,
            onClose = { lastIndex ->
                previewIndex = -1
                onPage(VkResultPosition.page(lastIndex))
            })
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "К параметрам") }
            Text(if (readOnly) "Сохранённые результаты" else "Результаты VK",
                style = MaterialTheme.typography.titleLarge)
        }
        job?.let { current ->
            Text(when (current.phase) {
                VkJobPhase.SCANNING -> "Идёт поиск"
                VkJobPhase.READY -> "Поиск завершён"
                VkJobPhase.DOWNLOADING -> "Идёт загрузка"
                VkJobPhase.FINISHED -> "Загрузка завершена"
                VkJobPhase.ERROR -> "Ошибка"
                VkJobPhase.CANCELLED -> "Задание остановлено"
            }, style = MaterialTheme.typography.titleMedium)
            Text("Найдено ${current.found} · выбрано ${current.selected} · скачано ${current.downloaded}")
            Text("Просмотрено публикаций: ${current.scannedPosts}" +
                if (current.totalPosts > 0) " из ${current.totalPosts}" else "",
                style = MaterialTheme.typography.bodySmall)
            current.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (current.phase == VkJobPhase.SCANNING || current.phase == VkJobPhase.DOWNLOADING)
                CircularProgressIndicator(Modifier.size(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!readOnly && !isFavoriteCheck && current.phase in setOf(VkJobPhase.SCANNING, VkJobPhase.DOWNLOADING))
                    OutlinedButton(onClick = onCancel) { Text("Остановить") }
                if (!readOnly && !isFavoriteCheck && current.phase in setOf(VkJobPhase.ERROR, VkJobPhase.CANCELLED) &&
                    !current.scanComplete)
                    OutlinedButton(onClick = onContinue) { Text("Продолжить") }
                if (readOnly && !isFavoriteCheck) OutlinedButton(onClick = onRepeat) { Text("Повторить поиск") }
            }
            if (!readOnly && current.found > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { onSelectAll(true) }) { Text("Выбрать все") }
                    OutlinedButton(onClick = { onSelectAll(false) }) { Text("Снять выделение") }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Страница ${page + 1}", Modifier.weight(1f))
            TextButton(onClick = {
                grid = !grid
                prefs.edit().putBoolean("results_grid", grid).apply()
            }) { Text(if (grid) "Сетка 4×" else "Список") }
            if (page > 0) TextButton(onClick = { onPage(page - 1) }) { Text("Назад") }
            if ((job?.found ?: 0) > (page + 1) * VkResultPosition.PAGE_SIZE)
                TextButton(onClick = { onPage(page + 1) }) { Text("Далее") }
        }
        if (images.any { it.candidate.adStatus == VkAdStatus.POSSIBLE })
            Text("Возможная реклама помечена · подтверждённая реклама скрыта",
                style = MaterialTheme.typography.bodySmall)
        if (images.isEmpty() && job?.phase == VkJobPhase.READY)
            Text("За выбранный период изображений не найдено")
        if (grid) {
            LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                gridItemsIndexed(images, key = { _, item -> item.candidate.photoKey }) { index, item ->
                    Box(Modifier.fillMaxWidth().aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { previewIndex = page * VkResultPosition.PAGE_SIZE + index }) {
                        RemotePreview(item.candidate.url, Modifier.fillMaxSize(), "vk")
                        RoundSelection(item.selected, isSelectable(item, readOnly),
                            Modifier.align(Alignment.TopStart)) { onSelect(item, it) }
                        if (item.candidate.adStatus != VkAdStatus.NONE)
                            AdBadge(item.candidate.adStatus,
                                Modifier.align(Alignment.BottomEnd).padding(3.dp), compact = true)
                    }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                itemsIndexed(images, key = { _, item -> item.candidate.photoKey }) { index, item ->
                    val newPost = index == 0 || images[index - 1].candidate.postKey != item.candidate.postKey
                    Column {
                        if (newPost) Row(Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Публикация · ${formatPostDate(item.candidate.postEpochSecond)}",
                                modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            if (!readOnly) TextButton(onClick = { onSelectPost(item, false) }) {
                                Text("Снять публикацию")
                            }
                        }
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                RoundSelection(item.selected, isSelectable(item, readOnly)) {
                                    onSelect(item, it)
                                }
                                Box(Modifier.size(72.dp).clip(RoundedCornerShape(7.dp))
                                    .clickable { previewIndex = page * VkResultPosition.PAGE_SIZE + index }) {
                                    RemotePreview(item.candidate.url, Modifier.fillMaxSize(), "vk")
                                }
                                Column(Modifier.padding(start = 10.dp).weight(1f)
                                    .clickable { previewIndex = page * VkResultPosition.PAGE_SIZE + index }) {
                                    Text("${item.candidate.width} × ${item.candidate.height}")
                                    Text(itemPhaseLabel(item.phase), style = MaterialTheme.typography.bodySmall)
                                    if (item.candidate.adStatus != VkAdStatus.NONE)
                                        AdBadge(item.candidate.adStatus)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!readOnly && job?.scanComplete == true && job.phase !in
            setOf(VkJobPhase.SCANNING, VkJobPhase.DOWNLOADING)) {
            val canVerifySaved = job.downloaded > 0 || job.duplicates > 0
            Button(onClick = onDownload, enabled = hasPendingSelection || canVerifySaved,
                modifier = Modifier.fillMaxWidth()) {
                Text(if (hasPendingSelection) "Скачать выбранное" else "Проверить файлы и восстановить")
            }
            if (job.failed > 0) TextButton(onClick = onRetry) { Text("Повторить ошибки") }
        }
        if (!readOnly && job?.scanComplete == true && onFinishReview != null) {
            OutlinedButton(onClick = onFinishReview, modifier = Modifier.fillMaxWidth()) {
                Text("Завершить просмотр")
            }
        }
    }
}

internal fun isSelectable(item: VkStoredImage, readOnly: Boolean): Boolean =
    !readOnly && item.phase in setOf(VkItemPhase.FOUND, VkItemPhase.ERROR)

@Composable
internal fun RoundSelection(checked: Boolean, enabled: Boolean,
    modifier: Modifier = Modifier, onChecked: (Boolean) -> Unit) {
    val color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(modifier.size(44.dp).toggleable(value = checked, enabled = enabled,
        role = Role.Checkbox, onValueChange = onChecked), contentAlignment = Alignment.Center) {
        Surface(Modifier.size(25.dp), shape = CircleShape, color =
            if (checked) color else MaterialTheme.colorScheme.surface,
            border = if (checked) null else BorderStroke(2.dp, color)) {
            if (checked) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
internal fun AdBadge(status: VkAdStatus, modifier: Modifier = Modifier, compact: Boolean = false) {
    val confirmed = status == VkAdStatus.CONFIRMED
    Surface(modifier, shape = RoundedCornerShape(5.dp),
        color = if (confirmed) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.tertiaryContainer) {
        Text(if (compact) if (confirmed) "Р" else "?" else
            if (confirmed) "Реклама VK" else "Возможная реклама",
            Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (confirmed) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

private val POST_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale("ru"))
internal fun formatPostDate(epochSecond: Long): String = Instant.ofEpochSecond(epochSecond)
    .atZone(ZoneId.systemDefault()).toLocalDate().format(POST_DATE)

private fun itemPhaseLabel(phase: VkItemPhase): String = when (phase) {
    VkItemPhase.FOUND -> "Найдено"
    VkItemPhase.DOWNLOADING -> "Скачивается"
    VkItemPhase.DONE -> "Скачано"
    VkItemPhase.DUPLICATE -> "Дубликат"
    VkItemPhase.ERROR -> "Ошибка"
}

package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.ui.components.AppCard
import com.domenota.medialoader.ui.components.DownloadRow
import com.domenota.medialoader.ui.components.GradientActionButton
import com.domenota.medialoader.ui.components.MusicHomeSection
import com.domenota.medialoader.ui.components.SectionTitle
import com.domenota.medialoader.ui.components.SourceIcon
import com.domenota.medialoader.ui.model.DownloadUiItem

@Composable
fun HomeScreen(
    url: String,
    onUrlChange: (String) -> Unit,
    onPasteClick: () -> Unit,
    onCheckClick: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSource: (String) -> Unit = {},
    recentDownloads: List<DownloadUiItem> = emptyList(),
    onOpenDownload: (String) -> Unit = {},
    onCancelDownload: (String) -> Unit = {},
    onShareDownload: (String) -> Unit = {},
    onHideDownload: (String) -> Unit = {},
    onRetryDownload: (String) -> Unit = {},
    onReanalyzeMissing: (String) -> Unit = {},
    onRenameDownload: (String, String) -> Unit = { _, _ -> },
    recognitionEnabled: Boolean = false,
    onRecognizeDownload: (String) -> Unit = {},
    title: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 24.dp)) {
        if (title != null) title() else Text("Загрузчик", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(5.dp))
        Text("Скачивание медиа из Instagram, YouTube, VK и RUTUBE",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(22.dp))
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier
                .background(Brush.linearGradient(listOf(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.surface,
                )))
                .padding(18.dp)) {
                Text("Что скачаем?", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(4.dp))
                Text("Фото, видео или музыку по ссылке",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(value = url, onValueChange = onUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Вставьте ссылку") },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (url.isNotEmpty()) IconButton(onClick = { onUrlChange("") }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Очистить ссылку")
                            }
                            IconButton(onClick = onPasteClick) {
                                Icon(Icons.Rounded.ContentPaste, contentDescription = "Вставить из буфера")
                            }
                        }
                    },
                    singleLine = true, shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline))
                Spacer(Modifier.height(14.dp))
                GradientActionButton("Найти файл", onClick = onCheckClick,
                    enabled = url.isNotBlank(), leadingIcon = Icons.Rounded.Search)
             }
        }
        Spacer(Modifier.height(24.dp))
        Text("Открыть источник", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceCard("instagram", "Instagram", Modifier.weight(1f), onOpenSource)
            SourceCard("youtube", "YouTube", Modifier.weight(1f), onOpenSource)
            SourceCard("vk", "VK", Modifier.weight(1f), onOpenSource)
            SourceCard("rutube", "RUTUBE", Modifier.weight(1f), onOpenSource)
        }
        Spacer(Modifier.height(24.dp))
        MusicHomeSection()
        if (recentDownloads.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            SectionTitle(title = "Последние загрузки", action = "Все", onActionClick = onOpenDownloads)
            Spacer(Modifier.height(12.dp))
            recentDownloads.forEach { item ->
                DownloadRow(item = item, onOpen = { onOpenDownload(item.id) },
                    onCancel = { onCancelDownload(item.id) }, onShare = { onShareDownload(item.id) },
                    onHide = { onHideDownload(item.id) }, onRetry = { onRetryDownload(item.id) },
                    onReanalyzeMissing = { onReanalyzeMissing(item.id) },
                    onRename = { onRenameDownload(item.id, it) },
                    recognitionEnabled = recognitionEnabled,
                    onRecognize = { onRecognizeDownload(item.id) })
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SourceCard(id: String, title: String, modifier: Modifier, onOpen: (String) -> Unit) {
    AppCard(modifier.clickable(onClickLabel = "Открыть $title") { onOpen(id) }) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SourceIcon(id, 36.dp)
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton
import com.domenota.medialoader.ui.components.FullSongSearchDialog
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.data.search.FullSongSource
import androidx.compose.material3.RadioButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.net.Uri
import com.domenota.medialoader.ui.components.AppCard
import com.domenota.medialoader.ui.components.GradientActionButton
import com.domenota.medialoader.ui.components.MediaSelectionMark
import com.domenota.medialoader.ui.components.RemotePreview
import com.domenota.medialoader.ui.components.RemoteMediaPreviewDialog
import com.domenota.medialoader.core.provider.PreviewPlayback
import com.domenota.medialoader.ui.model.AnalysisUiState
import com.domenota.medialoader.ui.model.MediaKind
import com.domenota.medialoader.ui.model.PreviewMediaUi
import com.domenota.medialoader.ui.theme.AccentPurple
import com.domenota.medialoader.core.recognition.MusicLookupPolicy
import com.domenota.medialoader.core.provider.YouTubeLinkParser
import com.domenota.medialoader.core.provider.VkLinkParser
import com.domenota.medialoader.core.provider.RutubeLinkParser
import com.domenota.medialoader.core.provider.InstagramLinkParser

private fun sourceHost(url: String): String = runCatching {
    Uri.parse(url).host?.removePrefix("www.") ?: ""
}.getOrDefault("")

private fun canSearchFullSong(items: List<PreviewMediaUi>): Boolean =
    items.any { item ->
        MusicLookupPolicy.supports(item.providerId) &&
            (item.kind == MediaKind.AUDIO || item.kind == MediaKind.VIDEO ||
                item.providerId == "instagram")
    }

@Composable
fun PreviewScreen(
    url: String,
    state: AnalysisUiState,
    items: List<PreviewMediaUi> = emptyList(),
    onBack: () -> Unit,
    onToggleItem: (String) -> Unit = {},
    onToggleAllPhotos: () -> Unit = {},
    onDownloadSelected: () -> Unit = {},
    onSaveFrame: (String) -> Unit = {},
    errorMessage: String? = null,
    busy: Boolean = false,
    onLogin: () -> Unit = {},
    onRetry: () -> Unit = {},
    asSheet: Boolean = false,
    onFindFullSong: () -> Unit = {},
    onCloseFullSong: () -> Unit = {},
    fullSongOpen: Boolean = false,
    fullSongBusy: Boolean = false,
    fullSongArtist: String = "",
    fullSongTitle: String = "",
    fullSongArtworkUrl: String? = null,
    fullSongMessage: String? = null,
    fullSongSessionId: Long = 0L,
    musicSource: FullSongSource = FullSongSource.HITMOZ,
    resolvePlayback: suspend (String) -> PreviewPlayback = { throw IllegalArgumentException("Предпросмотр недоступен") },
) {
    var viewingId by rememberSaveable { mutableStateOf<String?>(null) }
    val openPreview: (String) -> Unit = { viewingId = it }
    LaunchedEffect(state, items.map { it.id }) {
        if (state != AnalysisUiState.PREVIEW_READY || items.none { it.id == viewingId }) viewingId = null
    }
    viewingId?.takeIf { state == AnalysisUiState.PREVIEW_READY && items.any { item -> item.id == it } }?.let { id ->
        RemoteMediaPreviewDialog(id, items, resolvePlayback, onToggleItem, onDismiss = { viewingId = null })
    }
    if (fullSongOpen) {
        if (fullSongBusy) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Распознавание музыки") },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                        Text("Определяем композицию без сохранения фрагмента в загрузки…")
                    }
                },
                confirmButton = {
                    TextButton(onClick = onCloseFullSong) { Text("Отмена") }
                },
            )
        } else {
            // A new explicit launch gets a fresh input snapshot. Recomposition
            // of an existing session cannot replace manually entered text.
            key(fullSongSessionId, musicSource) {
                LaunchedEffect(fullSongSessionId, musicSource) {
                    AppLog.i("MusicSource", "Full-song window launched; session=" +
                        fullSongSessionId + "; adapter=" + musicSource.storedValue)
                }
                FullSongSearchDialog(
                    source = musicSource,
                    initialArtist = fullSongArtist,
                    initialTitle = fullSongTitle,
                    recognizedArtworkUrl = fullSongArtworkUrl,
                    onDismiss = onCloseFullSong,
                    recognitionMessage = fullSongMessage,
                )
            }
        }
    }
    if (asSheet && state == AnalysisUiState.PREVIEW_READY &&
        items.any { it.kind == MediaKind.IMAGE } &&
        items.none { it.kind == MediaKind.VIDEO }) {
        PhotoSelectionSheet(items, onToggleItem, onToggleAllPhotos, onDownloadSelected,
            errorMessage, busy, onFindFullSong, canSearchFullSong(items), openPreview)
        return
    }
    if (asSheet && state == AnalysisUiState.PREVIEW_READY &&
        items.isNotEmpty() && items.none { it.kind == MediaKind.IMAGE }) {
        VideoAudioSelectionSheet(url, items, onToggleItem, onDownloadSelected, errorMessage,
            busy, onFindFullSong, canSearchFullSong(items), onSaveFrame, openPreview)
        return
    }
    val contentModifier = if (asSheet) Modifier.fillMaxWidth()
        .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.82f).dp)
    else Modifier.fillMaxSize()
    Column(
        modifier = contentModifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        if (!asSheet) Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад")
            }
        }

        if (!asSheet) {
            Spacer(Modifier.height(10.dp))
            HeroPreview(state = state, previewUrl = items.firstOrNull()?.previewUrl,
                isVideo = items.firstOrNull()?.kind == MediaKind.VIDEO,
                onOpen = { (items.firstOrNull { it.selected } ?: items.firstOrNull())?.let { openPreview(it.id) } })
            Spacer(Modifier.height(18.dp))
        }

        Text(
            text = when (state) {
                AnalysisUiState.PREVIEW_READY -> if (asSheet) "Выбранный материал" else "Найденное медиа"
                AnalysisUiState.ACCESS_REQUIRED -> "Нужна авторизация"
                AnalysisUiState.ERROR -> "Не удалось проверить ссылку"
                else -> "Проверяем ссылку…"
            },
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(6.dp))
        if (asSheet && state == AnalysisUiState.PREVIEW_READY) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Box(Modifier.size(78.dp).background(MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(10.dp)).clickable {
                        (items.firstOrNull { it.selected } ?: items.firstOrNull())?.let { openPreview(it.id) }
                    }) {
                    RemotePreview(items.firstOrNull()?.previewUrl, Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f)) {
                    Text(url, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium)
                    Text(sourceHost(url),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else Text(
            text = url.ifBlank { "Ссылка не указана" },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state == AnalysisUiState.PREVIEW_READY) {
            val providerLabel = when (items.firstOrNull()?.providerId) {
                "instagram" -> "Instagram"
                "youtube" -> "YouTube"
                "vk" -> "VK"
                "rutube" -> "RUTUBE"
                else -> "Медиа"
            }
            Text("$providerLabel · ${items.count { it.kind != MediaKind.AUDIO }} файлов",
                style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(24.dp))

        when (state) {
            AnalysisUiState.ANALYZING -> AnalysisCard()
            AnalysisUiState.ACCESS_REQUIRED -> AccessRequiredCard(url = url, message = errorMessage, onLogin = onLogin)
            AnalysisUiState.ERROR -> ErrorCard(message = errorMessage, onRetry = onRetry)
            AnalysisUiState.PREVIEW_READY -> MediaList(
                items = items,
                onToggleItem = onToggleItem,
                onToggleAllPhotos = onToggleAllPhotos,
                onDownloadSelected = onDownloadSelected,
                busy = busy,
                errorMessage = errorMessage,
                onFindFullSong = onFindFullSong,
                allowFullSong = canSearchFullSong(items),
                onSaveFrame = onSaveFrame,
                onPreview = openPreview,
            )
            AnalysisUiState.IDLE -> AnalysisCard()
        }
    }
}

@Composable
private fun VideoAudioSelectionSheet(
    url: String,
    items: List<PreviewMediaUi>,
    onToggleItem: (String) -> Unit,
    onDownloadSelected: () -> Unit,
    errorMessage: String?,
    busy: Boolean,
    onFindFullSong: () -> Unit,
    allowFullSong: Boolean,
    onSaveFrame: (String) -> Unit,
    onPreview: (String) -> Unit,
) {
    val height = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
    Column(Modifier.fillMaxWidth().height(height).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text("Выбранный материал", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(78.dp).background(MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(10.dp)).clickable {
                    (items.firstOrNull { it.selected } ?: items.firstOrNull())?.let { onPreview(it.id) }
                }) {
                RemotePreview(items.firstOrNull()?.previewUrl, Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f)) {
                Text(url, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(sourceHost(url),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(22.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            val audioItems = items.filter { it.kind == MediaKind.AUDIO }
            audioItems.forEach { item ->
                Text(if (item.providerId == "instagram") "Аудио из публикации" else "Аудио",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                MediaRow(item, onToggle = { onToggleItem(item.id) }, onPreview = { onPreview(item.id) },
                    singleChoice = items.none { it.kind == MediaKind.IMAGE })
            }
            if (allowFullSong && audioItems.isNotEmpty()) {
                FullSongTextLink(onClick = onFindFullSong, enabled = !busy)
            }
            if (items.any { it.kind == MediaKind.AUDIO } && items.any { it.kind == MediaKind.VIDEO }) {
                HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outline)
            }
            items.filter { it.kind == MediaKind.VIDEO }.forEach { item ->
                Text("Видео", color = MaterialTheme.colorScheme.onSurfaceVariant)
                MediaRow(item, onToggle = { onToggleItem(item.id) }, onPreview = { onPreview(item.id) },
                    singleChoice = items.none { it.kind == MediaKind.IMAGE })
                if (item.providerId == "instagram") FullSongTextLink(
                    onClick = { onSaveFrame(item.id) }, enabled = !busy,
                    text = "Скачать кадр")
            }
            if (allowFullSong && audioItems.isEmpty()) {
                FullSongTextLink(onClick = onFindFullSong, enabled = !busy)
            }
            if (sourceHost(url).endsWith("youtube.com") &&
                items.count { it.kind == MediaKind.VIDEO } == 1 &&
                items.any { it.kind == MediaKind.VIDEO && it.subtitle.startsWith("360p") }) {
                Text(
                    "YouTube вернул только 360p для этого ролика. Другие качества пока недоступны; " +
                        "можно попробовать обновить yt-dlp в настройках.",
                    modifier = Modifier.padding(top = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        GradientActionButton(text = if (busy) "Добавляем…" else "Скачать выбранное",
            onClick = onDownloadSelected, enabled = items.any { it.selected } && !busy)
        errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun FullSongTextLink(onClick: () -> Unit, enabled: Boolean, text: String = "Найти полную песню") {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        color = if (enabled) AccentPurple else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun PhotoSelectionSheet(
    items: List<PreviewMediaUi>,
    onToggleItem: (String) -> Unit,
    onToggleAllPhotos: () -> Unit,
    onDownloadSelected: () -> Unit,
    errorMessage: String?,
    busy: Boolean,
    onFindFullSong: () -> Unit,
    allowFullSong: Boolean,
    onPreview: (String) -> Unit,
) {
    val photos = items.filter { it.kind == MediaKind.IMAGE }
    val audio = items.filter { it.kind == MediaKind.AUDIO }
    val selected = items.count { it.selected }
    val selectedPhotos = photos.count { it.selected }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
    Column(Modifier.fillMaxWidth().height(maxHeight).padding(horizontal = 16.dp)) {
        Text("Выбранный материал", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggleAllPhotos)
            .padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            MediaSelectionMark(selectedPhotos == photos.size)
            Text(if (selectedPhotos == photos.size) "Все фото выбраны" else "Выбрать все фото",
                Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            audio.forEach { item ->
                Text(if (item.providerId == "instagram") "Аудио из публикации" else "Аудио",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                MediaRow(item, onToggle = { onToggleItem(item.id) }, onPreview = { onPreview(item.id) },
                    singleChoice = items.none { it.kind == MediaKind.IMAGE })
                Spacer(Modifier.height(10.dp))
            }
            if (allowFullSong) FullSongTextLink(onClick = onFindFullSong, enabled = !busy)
            if (audio.isNotEmpty() && photos.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(bottom = 16.dp), color = MaterialTheme.colorScheme.outline)
            }
            photos.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { item ->
                        Box(Modifier.weight(1f).aspectRatio(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .clickable { onPreview(item.id) }
                            .semantics { contentDescription = "Предпросмотр: ${item.title}" }) {
                            RemotePreview(item.previewUrl, Modifier.fillMaxSize())
                            Box(Modifier.align(Alignment.TopStart)) {
                                MediaSelectionMark(item.selected, { onToggleItem(item.id) }, "Выбрать: ${item.title}",
                                    atThumbnailCorner = true)
                            }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(5.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        GradientActionButton(text = if (busy) "Добавляем…" else "Скачать ($selected)",
            onClick = onDownloadSelected, enabled = selected > 0 && !busy)
        errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun HeroPreview(state: AnalysisUiState, previewUrl: String?, isVideo: Boolean, onOpen: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clickable(enabled = state == AnalysisUiState.PREVIEW_READY, onClick = onOpen)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF1A2130), Color(0xFF242A38)),
                ),
                shape = RoundedCornerShape(20.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (state == AnalysisUiState.PREVIEW_READY) {
            RemotePreview(previewUrl, Modifier.fillMaxSize())
        }
        when (state) {
            AnalysisUiState.ANALYZING, AnalysisUiState.IDLE -> CircularProgressIndicator(
                color = AccentPurple,
                strokeWidth = 3.dp,
            )
            AnalysisUiState.ACCESS_REQUIRED -> Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnalysisUiState.ERROR -> Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            AnalysisUiState.PREVIEW_READY -> {
                if (isVideo) Box(
                modifier = Modifier
                    .size(62.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(34.dp),
                )
                }
                Unit
            }
        }
    }
}

@Composable
private fun AnalysisCard() {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Проверяем ссылку…", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Ищем доступные фото, видео и аудиодорожку. Обычно это занимает несколько секунд.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AccessRequiredCard(url: String, message: String?, onLogin: () -> Unit) {
    val source = when {
        YouTubeLinkParser.parse(url) != null -> "YouTube"
        VkLinkParser.parse(url) != null -> "VK"
        RutubeLinkParser.parse(url) != null -> "RUTUBE"
        InstagramLinkParser.parse(url) != null -> "Instagram"
        else -> null
    }
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Требуется авторизация", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                message?.takeIf { it.isNotBlank() }
                    ?: if (source != null)
                        "Для доступа к этому медиа необходимо войти в аккаунт $source."
                    else "Для доступа к этому медиа необходимо войти в аккаунт источника.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            GradientActionButton(
                text = source?.let { "Войти в $it" } ?: "Открыть авторизацию",
                onClick = onLogin,
                leadingIcon = Icons.Rounded.Lock,
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String?, onRetry: () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Не удалось получить медиа", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                message ?: "Проверьте ссылку и попробуйте ещё раз.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GradientActionButton(text = "Повторить", onClick = onRetry)
        }
    }
}

@Composable
private fun MediaList(
    items: List<PreviewMediaUi>,
    onToggleItem: (String) -> Unit,
    onToggleAllPhotos: () -> Unit,
    onDownloadSelected: () -> Unit,
    busy: Boolean,
    errorMessage: String?,
    onFindFullSong: () -> Unit,
    allowFullSong: Boolean,
    onSaveFrame: (String) -> Unit,
    onPreview: (String) -> Unit,
) {
    val selectedCount = items.count { it.selected }
    val photos = items.filter { it.kind == MediaKind.IMAGE }
    if (items.isEmpty()) {
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Instagram не вернул доступных файлов для этой ссылки.",
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        items.filter { it.kind != MediaKind.IMAGE }.forEach { item ->
            MediaRow(item = item, onToggle = { onToggleItem(item.id) }, onPreview = { onPreview(item.id) },
                    singleChoice = items.none { it.kind == MediaKind.IMAGE })
            if (item.kind == MediaKind.VIDEO && item.providerId == "instagram")
                FullSongTextLink(onClick = { onSaveFrame(item.id) }, enabled = !busy,
                    text = "Скачать кадр")
            Spacer(Modifier.height(10.dp))
        }
        if (photos.isNotEmpty() && allowFullSong) {
            FullSongTextLink(onClick = onFindFullSong, enabled = !busy)
        }
        if (photos.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleAllPhotos)
                .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                MediaSelectionMark(photos.all { it.selected })
                Text(if (photos.all { it.selected }) "Все фото выбраны" else "Выбрать все фото",
                    modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
            }
            photos.chunked(3).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { item ->
                        Box(modifier = Modifier.weight(1f).aspectRatio(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .clickable { onPreview(item.id) }
                            .semantics { contentDescription = "Предпросмотр: ${item.title}" }) {
                            RemotePreview(item.previewUrl, Modifier.fillMaxSize())
                            Box(modifier = Modifier.align(Alignment.TopStart)) {
                                MediaSelectionMark(item.selected, { onToggleItem(item.id) }, "Выбрать: ${item.title}",
                                    atThumbnailCorner = true)
                            }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(5.dp))
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    if (photos.isNotEmpty()) Text("Выбрано: $selectedCount файлов", style = MaterialTheme.typography.bodyMedium)
    GradientActionButton(
        text = when {
            busy -> "Добавляем…"
            selectedCount == 0 -> "Выберите файлы"
            else -> "Скачать"
        },
        onClick = onDownloadSelected,
        enabled = selectedCount > 0 && !busy,
        leadingIcon = Icons.Rounded.Download,
    )
    if (allowFullSong && photos.isEmpty()) {
        Spacer(Modifier.height(10.dp))
        GradientActionButton(text = "Найти полную песню",
            onClick = onFindFullSong, enabled = !busy)
        Text("Можно скачать и звук публикации, и полную композицию.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    errorMessage?.let {
        Spacer(Modifier.height(10.dp))
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
internal fun MediaRow(
    item: PreviewMediaUi,
    onToggle: () -> Unit,
    onPreview: () -> Unit,
    singleChoice: Boolean = false,
) {
    val icon = when (item.kind) {
        MediaKind.VIDEO -> Icons.Rounded.VideoFile
        MediaKind.AUDIO -> Icons.Rounded.AudioFile
        MediaKind.IMAGE -> Icons.Rounded.Image
    }

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (singleChoice) Modifier.selectable(item.selected, role = Role.RadioButton, onClick = onToggle)
                    else Modifier.toggleable(item.selected, role = Role.Checkbox, onValueChange = { onToggle() }))
                .semantics { contentDescription = "Выбрать формат: ${item.title}" }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clickable(role = Role.Button, onClick = onPreview)
                    .semantics { contentDescription = "Предпросмотр: ${item.title}" }
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) {
                RemotePreview(item.previewUrl, Modifier.fillMaxSize())
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.BottomEnd).size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = when (item.kind) {
                        MediaKind.VIDEO -> "MP4"
                        MediaKind.AUDIO -> if (item.providerId == "instagram")
                            "Аудио из публикации" else "Аудио · MP3"
                        MediaKind.IMAGE -> item.title
                    },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.sizeLabel?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (singleChoice) RadioButton(selected = item.selected, onClick = onToggle,
                modifier = Modifier.semantics { contentDescription = "Выбрать: ${item.title}" })
            else MediaSelectionMark(item.selected, onToggle, "Выбрать: ${item.title}")
        }
    }
}

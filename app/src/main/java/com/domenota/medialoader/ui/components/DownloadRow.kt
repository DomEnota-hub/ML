package com.domenota.medialoader.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.core.storage.StorageNaming
import com.domenota.medialoader.core.recognition.MusicLookupPolicy
import com.domenota.medialoader.ui.model.DownloadUiItem
import com.domenota.medialoader.ui.model.DownloadUiState
import com.domenota.medialoader.data.storage.SavedFileStatus
import com.domenota.medialoader.ui.model.MediaKind
import com.domenota.medialoader.ui.theme.AccentPurple
import com.domenota.medialoader.ui.theme.Success
import com.domenota.medialoader.data.search.FullSongSource
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.data.MediaRepository

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DownloadRow(
    item: DownloadUiItem,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onShare: () -> Unit = {},
    onHide: () -> Unit = {},
    onRetry: () -> Unit = {},
    onReanalyzeMissing: () -> Unit = {},
    onRename: (String) -> Unit = {},
    recognitionEnabled: Boolean = false,
    onRecognize: () -> Unit = {},
    favorite: Boolean = false,
    folderLabel: String? = null,
    onToggleFavorite: (() -> Unit)? = null,
    onChooseFolder: (() -> Unit)? = null,
    onMoveToPrivate: (() -> Unit)? = null,
    onRestoreFromPrivate: (() -> Unit)? = null,
    allowSharing: Boolean = true,
    privateDeletion: Boolean = false,
    compact: Boolean = false,
) {
    val context = LocalContext.current
    LaunchedEffect(item.id, item.state, item.previewUrl) {
        if (item.providerId == "muzmo" && item.state == DownloadUiState.COMPLETED &&
            item.previewUrl == null) {
            MediaRepository.get(context).resolveMuzmoArtwork(item.id)
        }
    }
    var renameDialog by remember { mutableStateOf(false) }
    var sourceDialog by remember { mutableStateOf(false) }
    var selectedMusicSource by remember { mutableStateOf<FullSongSource?>(null) }
    var newName by remember(item.title) { mutableStateOf(StorageNaming.editableStem(item.title)) }

    val sourceUrl = item.sourcePageUrl?.takeUnless {
        item.kind == MediaKind.AUDIO && item.providerId?.lowercase() in setOf("hitmoz", "muzmo")
    }
    if (sourceDialog && sourceUrl != null) AlertDialog(
        onDismissRequest = { sourceDialog = false },
        title = { Text("Исходник") },
        text = {
            OutlinedTextField(
                value = sourceUrl,
                onValueChange = {},
                readOnly = true,
                label = { Text("Ссылка") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            NeutralOutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Исходник", sourceUrl))
                    sourceDialog = false
                },
            ) { Text("Копировать") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                NeutralOutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(sourceUrl))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                        sourceDialog = false
                    },
                ) { Text("Открыть") }
                NeutralOutlinedButton(onClick = { sourceDialog = false }) { Text("Закрыть") }
            }
        },
    )
    if (renameDialog) AlertDialog(
        onDismissRequest = { renameDialog = false },
        title = { Text("Переименовать файл") },
        text = {
            OutlinedTextField(
                newName,
                { newName = it },
                singleLine = true,
                label = { Text("Имя файла") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(newName); renameDialog = false },
                enabled = newName.isNotBlank(),
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("Отмена") } },
    )

    if (selectedMusicSource != null && !item.recognizedArtist.isNullOrBlank() && !item.recognizedTitle.isNullOrBlank()) {
        FullSongSearchDialog(
            source = selectedMusicSource!!,
            initialArtist = item.recognizedArtist,
            initialTitle = item.recognizedTitle,
            recognizedArtworkUrl = item.recognizedArtworkUrl,
            recognitionMessage = null,
            onDismiss = { selectedMusicSource = null },
        )
    }

    val missing = item.state == DownloadUiState.COMPLETED && item.fileStatus == SavedFileStatus.MISSING
    val inaccessible = item.state == DownloadUiState.COMPLETED &&
        item.fileStatus == SavedFileStatus.INACCESSIBLE
    val (statusText, statusColor) = when {
        missing -> "Файл отсутствует" to MaterialTheme.colorScheme.error
        inaccessible -> "Нет доступа к файлу" to MaterialTheme.colorScheme.error
        else -> when (item.state) {
        DownloadUiState.QUEUED -> "В очереди" to MaterialTheme.colorScheme.onSurfaceVariant
        DownloadUiState.DOWNLOADING -> "Загружается…" to AccentPurple
        DownloadUiState.COMPLETED -> "Сохранено" to if (MaterialTheme.colorScheme.background.luminance() > 0.5f)
            Color(0xFF16734D) else Success
        DownloadUiState.FAILED -> "Ошибка загрузки" to MaterialTheme.colorScheme.error
        DownloadUiState.CANCELLED -> "Отменено" to MaterialTheme.colorScheme.onSurfaceVariant
        }
    }

    var menuExpanded by remember { mutableStateOf(false) }
    val sourceLabel = when (item.providerId?.lowercase()) {
        "instagram" -> "Instagram"
        "youtube" -> "YouTube"
        "vk" -> "VK"
        "rutube" -> "RUTUBE"
        "hitmoz", "muzmo" -> "Музыка"
        else -> "Другое"
    }
    val type = item.title.substringAfterLast('.', "").uppercase().ifBlank { item.subtitle }
    val quality = Regex("[0-9]{3,4}p(?:60)?", RegexOption.IGNORE_CASE).find(item.title)?.value
    val fileMenu: @Composable () -> Unit = {
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (item.state == DownloadUiState.COMPLETED) {
                        if (onToggleFavorite != null) DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Rounded.Star, contentDescription = null) },
                            text = { Text(if (favorite) "Убрать из избранного" else "В избранное") },
                            onClick = { menuExpanded = false; onToggleFavorite() },
                        )
                        if (onChooseFolder != null) DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                            text = { Text(if (folderLabel == null) "Добавить в папку" else "Сменить папку") },
                            onClick = { menuExpanded = false; onChooseFolder() },
                        )
                        if (!missing && !inaccessible) {
                            DropdownMenuItem(leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                            text = { Text("Открыть") }, onClick = { menuExpanded = false; onOpen() })
                            if (allowSharing) DropdownMenuItem(leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                            text = { Text("Поделиться") }, onClick = { menuExpanded = false; onShare() })
                        }
                        if (missing) {
                            DropdownMenuItem(leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                            text = { Text("Найти и скачать заново") },
                                onClick = { menuExpanded = false; onReanalyzeMissing() })
                        }
                        if (!missing && !inaccessible && onMoveToPrivate != null) DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                            text = { Text("Переместить в личное") },
                            onClick = { menuExpanded = false; onMoveToPrivate() })
                        if (onRestoreFromPrivate != null) DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                            text = { Text("Вернуть в загрузки") },
                            onClick = { menuExpanded = false; onRestoreFromPrivate() })
                        if (sourceUrl != null) {
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                            text = { Text("Исходник") },
                                onClick = {
                                    menuExpanded = false
                                    sourceDialog = true
                                },
                            )
                        }
                        if (!missing && !inaccessible) DropdownMenuItem(leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                            text = { Text("Переименовать") }, onClick = {
                            menuExpanded = false
                            newName = StorageNaming.editableStem(item.title)
                            renameDialog = true
                        })
                        if (!missing && !inaccessible && item.kind == MediaKind.AUDIO &&
                            MusicLookupPolicy.supports(item.providerId) &&
                            !item.recognizedArtist.isNullOrBlank() && !item.recognizedTitle.isNullOrBlank()) {
                            DropdownMenuItem(
                                leadingIcon = { MusicSearchActionIcon() },
                            text = { Text("Найти полную песню") },
                                onClick = {
                                    menuExpanded = false
                                    selectedMusicSource = FullSongSource.fromStored(
                                        context.getSharedPreferences("ui", 0)
                                            .getString(FullSongSource.PREF_KEY, null),
                                    )
                                    AppLog.i("MusicSource", "History full-song launch · adapter=${selectedMusicSource?.storedValue}")
                                },
                            )
                        }
                        if (!missing && !inaccessible && recognitionEnabled && item.kind == MediaKind.AUDIO) {
                            DropdownMenuItem(
                                leadingIcon = { MusicSearchActionIcon(recognition = true) },
                            text = { Text(if (item.recognized) "Распознать снова" else "Найти название трека") },
                                onClick = { menuExpanded = false; onRecognize() },
                            )
                        }
                        if (!inaccessible) DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            text = { Text(if (privateDeletion) "Удалить навсегда" else if (missing) "Удалить запись" else "Удалить",
                                color = MaterialTheme.colorScheme.error) },
                            onClick = { menuExpanded = false; onHide() },
                        )
                    } else if (item.state == DownloadUiState.DOWNLOADING || item.state == DownloadUiState.QUEUED) {
                        DropdownMenuItem(leadingIcon = { Icon(Icons.Rounded.Close, contentDescription = null) },
                            text = { Text("Отмена") }, onClick = { menuExpanded = false; onCancel() })
                    } else {
                        DropdownMenuItem(leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                            text = { Text("Повторить") }, onClick = { menuExpanded = false; onRetry() })
                        DropdownMenuItem(leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            text = { Text("Удалить",
                            color = MaterialTheme.colorScheme.error) },
                            onClick = { menuExpanded = false; onHide() })
                    }
                }
    }
    if (compact) {
        Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = item.title; stateDescription = statusText }
            .combinedClickable(
                onClick = { if (item.state == DownloadUiState.COMPLETED) onOpen() else menuExpanded = true },
                onLongClickLabel = "Действия с файлом", onLongClick = { menuExpanded = true })) {
            DownloadThumbnail(item.savedUri, item.previewUrl, item.kind, Modifier.fillMaxSize())
            if (item.kind != MediaKind.IMAGE) Icon(
                if (item.kind == MediaKind.VIDEO) Icons.Rounded.Videocam else Icons.Rounded.MusicNote,
                contentDescription = if (item.kind == MediaKind.VIDEO) "Видео" else "Музыка",
                tint = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(5.dp)).padding(3.dp).size(16.dp))
            if (missing || inaccessible || item.state != DownloadUiState.COMPLETED) Icon(Icons.Rounded.ErrorOutline,
                contentDescription = statusText, tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(5.dp)).size(20.dp))
            VideoWatchStripe(item, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp))
            fileMenu()
        }
    } else AppCard(modifier = modifier.fillMaxWidth()) {
        // The whole completed row, including the thumbnail, opens the file.
        // Do not silently disable taps because a cached existence check returned
        // INACCESSIBLE: the action handler rechecks and shows a precise error.
        Row(Modifier.fillMaxWidth()
            .clickable(enabled = item.state == DownloadUiState.COMPLETED, onClick = onOpen)
            .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(82.dp).clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)),
            ) {
                DownloadThumbnail(item.savedUri, item.previewUrl, item.kind, Modifier.fillMaxSize())
                VideoWatchStripe(item, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SourceIcon(item.providerId, 22.dp)
                    Text(sourceLabel, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Text(item.title, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                val details = listOfNotNull(type, quality, item.sizeLabel, item.durationLabel).joinToString(" · ")
                Text(if (item.state == DownloadUiState.FAILED) item.subtitle else details,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (item.state == DownloadUiState.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.recognitionLabel?.let { recognition ->
                    Text(
                        recognition,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                item.dateLabel?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (item.state == DownloadUiState.COMPLETED && (favorite || folderLabel != null)) {
                    Text(listOfNotNull(if (favorite) "★ Избранное" else null, folderLabel)
                        .joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                if (item.state != DownloadUiState.COMPLETED || missing || inaccessible) {
                    Text(statusText, style = MaterialTheme.typography.labelLarge, color = statusColor)
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Действия с файлом")
                }
                fileMenu()
            }
        }
        if (item.state == DownloadUiState.DOWNLOADING) {
            SimpleProgressBar(item.progress)
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun SimpleProgressBar(progress: Float?) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f)
    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
        val y = size.height / 2f
        val width = size.width
        drawLine(track, Offset(0f, y), Offset(width, y), strokeWidth = size.height, cap = StrokeCap.Round)
        val fraction = progress?.coerceIn(0f, 1f) ?: 0.12f
        if (fraction > 0f) {
            drawLine(
                AccentPurple,
                Offset(0f, y),
                Offset(width * fraction, y),
                strokeWidth = size.height,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun MusicSearchActionIcon(recognition: Boolean = false) {
    Box(Modifier.size(24.dp)) {
        Icon(if (recognition) Icons.Rounded.GraphicEq else Icons.Rounded.MusicNote,
            contentDescription = null, modifier = Modifier.align(Alignment.TopStart).size(19.dp))
        Icon(Icons.Rounded.Search, contentDescription = null,
            modifier = Modifier.align(Alignment.BottomEnd).size(13.dp))
    }
}

package com.domenota.medialoader.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.domenota.medialoader.ui.components.NeutralOutlinedButton as OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.editor.AudioEditSpec
import com.domenota.medialoader.data.editor.EditorExportState
import com.domenota.medialoader.ui.components.AudioWaveformTimeline
import com.domenota.medialoader.ui.components.MediaTrimControls
import com.domenota.medialoader.ui.components.formatPreciseMediaTime
import com.domenota.medialoader.ui.components.rememberEditorExitRequest
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AudioSourceInfo(
    val durationMs: Long,
    val title: String,
    val artist: String,
    val album: String,
    val cover: Bitmap?,
)

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioEditorScreen(
    item: DownloadEntity,
    exportState: EditorExportState,
    onBack: () -> Unit,
    onExport: (AudioEditSpec) -> Unit,
    onCancelExport: () -> Unit,
) {
    val context = LocalContext.current
    val uri = item.savedUri?.let(Uri::parse)
    val ownExport = exportState.exporting && exportState.sourceId == item.id
    val busyWithOther = exportState.exporting && exportState.sourceId != null &&
        exportState.sourceId != item.id
    var sourceInfo by remember(uri) { mutableStateOf<AudioSourceInfo?>(null) }
    var title by remember(item.id) {
        mutableStateOf(item.recognizedTitle ?: item.sourceAudioTitle ?: "")
    }
    var artist by remember(item.id) {
        mutableStateOf(item.recognizedArtist ?: item.sourceAudioArtist ?: "")
    }
    var album by remember(item.id) { mutableStateOf(item.recognizedAlbum ?: "") }
    var trimStart by remember(item.id) { mutableLongStateOf(0L) }
    var trimEnd by remember(item.id) { mutableLongStateOf(0L) }
    var replacementCoverUri by remember(item.id) { mutableStateOf<String?>(null) }
    var replacementCover by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    var removeCover by remember(item.id) { mutableStateOf(false) }

    val initialTitle = item.recognizedTitle ?: item.sourceAudioTitle ?: sourceInfo?.title.orEmpty()
    val initialArtist = item.recognizedArtist ?: item.sourceAudioArtist ?: sourceInfo?.artist.orEmpty()
    val initialAlbum = item.recognizedAlbum ?: sourceInfo?.album.orEmpty()
    val fullDuration = sourceInfo?.durationMs ?: 0L
    val hasUnsavedChanges = title != initialTitle || artist != initialArtist || album != initialAlbum ||
        trimStart != 0L || (fullDuration > 0L && trimEnd != 0L && trimEnd != fullDuration) ||
        replacementCoverUri != null || removeCover
    val requestExit = rememberEditorExitRequest(
        hasUnsavedChanges = hasUnsavedChanges && !ownExport,
        onExit = onBack,
    )
    fun resetEdits() {
        title = initialTitle
        artist = initialArtist
        album = initialAlbum
        trimStart = 0L
        trimEnd = fullDuration
        replacementCoverUri = null
        replacementCover = null
        removeCover = false
    }

    val previewPlayer = remember(uri) {
        uri?.let { sourceUri ->
            ExoPlayer.Builder(context)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    true,
                )
                .setHandleAudioBecomingNoisy(true)
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri(sourceUri))
                    prepare()
                    playWhenReady = false
                }
        }
    }
    DisposableEffect(previewPlayer) {
        onDispose { previewPlayer?.release() }
    }

    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    picked,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            replacementCoverUri = picked.toString()
            removeCover = false
            replacementCover = decodeCover(context, picked)
        }
    }

    LaunchedEffect(uri) {
        if (uri == null) return@LaunchedEffect
        val info = withContext(Dispatchers.IO) { readAudioInfo(context, uri) }
        sourceInfo = info
        if (title.isBlank()) title = info?.title.orEmpty()
        if (artist.isBlank()) artist = info?.artist.orEmpty()
        if (album.isBlank()) album = info?.album.orEmpty()
        trimEnd = info?.durationMs ?: 0L
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Редактор аудио") },
                navigationIcon = {
                    IconButton(onClick = requestExit) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    TextButton(
                        onClick = ::resetEdits,
                        enabled = hasUnsavedChanges && !ownExport,
                    ) {
                        Text("Сбросить")
                    }
                },
            )
        },
    ) { padding ->
        if (uri == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Файл недоступен")
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            val shownCover = when {
                removeCover -> null
                replacementCover != null -> replacementCover
                else -> sourceInfo?.cover
            }
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .align(Alignment.CenterHorizontally)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(26.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (shownCover != null) {
                    Image(
                        bitmap = shownCover.asImageBitmap(),
                        contentDescription = "Обложка",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(220.dp),
                    )
                } else {
                    Text(
                        "♪",
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = { coverPicker.launch(arrayOf("image/*")) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Image, contentDescription = null)
                    Text("  Обложка")
                }
                OutlinedButton(
                    onClick = {
                        replacementCoverUri = null
                        replacementCover = null
                        removeCover = true
                    },
                    enabled = shownCover != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                    Text("  Удалить")
                }
            }

            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(200) },
                label = { Text("Название") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = artist,
                onValueChange = { artist = it.take(200) },
                label = { Text("Исполнитель") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = album,
                onValueChange = { album = it.take(200) },
                label = { Text("Альбом") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(24.dp))
            Text("Обрезка", style = MaterialTheme.typography.titleMedium)
            val duration = sourceInfo?.durationMs ?: 0L
            if (duration > 0L) {
                MediaTrimControls(
                    player = requireNotNull(previewPlayer),
                    durationMs = duration,
                    trimStartMs = trimStart,
                    trimEndMs = trimEnd,
                    onTrimChange = { start, end ->
                        trimStart = start
                        trimEnd = end
                    },
                    modifier = Modifier.fillMaxWidth(),
                    timelineVisual = { visualModifier ->
                        AudioWaveformTimeline(
                            uri = uri,
                            durationMs = duration,
                            modifier = visualModifier,
                        )
                    },
                )
                Text(
                    "Итоговая длительность: ${formatPreciseMediaTime((trimEnd - trimStart).coerceAtLeast(0L))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Тап по полосе запускает предпросмотр с выбранной точки до правой границы.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("Определяем длительность…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(26.dp))
            if (ownExport) {
                Text("${exportState.operation} · ${exportState.progress}%")
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { exportState.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Можно выйти из редактора — обработка продолжится в фоне.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onCancelExport) { Text("Отменить") }
            } else {
                if (busyWithOther) {
                    Text(
                        "Сейчас обрабатывается другой файл. Дождись завершения или отмени обработку из уведомления.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                Button(
                    onClick = {
                        onExport(
                            AudioEditSpec(
                                startMs = trimStart,
                                endMs = trimEnd.takeIf { end ->
                                    end > 0L && end < (sourceInfo?.durationMs ?: Long.MAX_VALUE)
                                },
                                title = title.trim(),
                                artist = artist.trim(),
                                album = album.trim(),
                                replacementCoverUri = replacementCoverUri,
                                removeCover = removeCover,
                            ),
                        )
                    },
                    enabled = sourceInfo != null && !busyWithOther,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Text("Сохранить копию")
                }
                Text(
                    "Сохраняется отдельный MP3 192 кбит/с. Исходный файл не изменяется.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            exportState.message
                ?.takeIf { exportState.sourceId == null || exportState.sourceId == item.id }
                ?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun readAudioInfo(context: android.content.Context, uri: Uri): AudioSourceInfo? =
    runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty()
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
            val cover = retriever.embeddedPicture?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            AudioSourceInfo(duration, title, artist, album, cover)
        } finally {
            retriever.release()
        }
    }.getOrNull()

private fun decodeCover(context: android.content.Context, uri: Uri): Bitmap? =
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }
    }.getOrNull()


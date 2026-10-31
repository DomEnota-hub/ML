package com.domenota.medialoader.ui.screens

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.domenota.medialoader.ui.components.NeutralOutlinedButton as OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.editor.CropAspectPreset
import com.domenota.medialoader.data.editor.CropFractions
import com.domenota.medialoader.data.editor.VideoCropMath
import com.domenota.medialoader.data.editor.VideoEditSpec
import com.domenota.medialoader.data.editor.VideoEditor
import com.domenota.medialoader.data.editor.VideoFramePosition
import com.domenota.medialoader.data.editor.EditorExportState
import com.domenota.medialoader.ui.components.CropFrameOverlay
import com.domenota.medialoader.ui.components.EditorTextDialog
import com.domenota.medialoader.ui.components.TextEditorVideoSource
import com.domenota.medialoader.data.editor.TextLayer
import com.domenota.medialoader.data.editor.TextLayerRenderer
import com.domenota.medialoader.ui.components.MediaTrimControls
import com.domenota.medialoader.ui.components.VideoScrubFrame
import com.domenota.medialoader.ui.components.VideoTimelineStrip
import com.domenota.medialoader.ui.components.rememberEditorExitRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.io.File

private data class VideoInfo(
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val frameDurationMs: Long?,
)

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoEditorScreen(
    item: DownloadEntity,
    exportState: EditorExportState,
    onBack: () -> Unit,
    onExport: (VideoEditSpec) -> Unit,
    onSaveFrame: (Long, VideoEditSpec) -> Unit,
    onExtractAudio: (Long, Long?) -> Unit,
    onCancelExport: () -> Unit,
) {
    val context = LocalContext.current
    val uri = item.savedUri?.let(Uri::parse)
    val ownExport = exportState.exporting && exportState.sourceId == item.id
    val busyWithOther = exportState.exporting && exportState.sourceId != null &&
        exportState.sourceId != item.id
    var info by remember(uri) { mutableStateOf<VideoInfo?>(null) }
    var crop by remember { mutableStateOf(CropFractions()) }
    var cropPreset by remember { mutableStateOf(CropAspectPreset.FREE) }
    var rotation by remember { mutableIntStateOf(0) }
    var removeAudio by remember { mutableStateOf(false) }
    var reverseVideo by remember(item.id) { mutableStateOf(false) }
    var reversePreviewFile by remember(item.id) { mutableStateOf<File?>(null) }
    var reversePreviewBusy by remember(item.id) { mutableStateOf(false) }
    var reversePreviewPercent by remember(item.id) { mutableIntStateOf(0) }
    var reversePreviewError by remember(item.id) { mutableStateOf<String?>(null) }
    var reversePreviewJob by remember(item.id) { mutableStateOf<Job?>(null) }
    val previewScope = rememberCoroutineScope()
    var trimStart by remember { mutableLongStateOf(0L) }
    var trimEnd by remember { mutableLongStateOf(0L) }
    var textLayers by remember(item.id) { mutableStateOf<List<TextLayer>>(emptyList()) }
    var textEditorOpen by remember(item.id) { mutableStateOf(false) }
    var showTextResult by remember(item.id) { mutableStateOf(false) }

    val fullDuration = info?.durationMs ?: 0L
    val hasUnsavedChanges = crop != CropFractions() ||
        cropPreset != CropAspectPreset.FREE ||
        rotation != 0 || removeAudio || reverseVideo || trimStart != 0L || textLayers.isNotEmpty() ||
        (fullDuration > 0L && trimEnd != 0L && trimEnd != fullDuration)
    val requestExit = rememberEditorExitRequest(
        hasUnsavedChanges = hasUnsavedChanges && !ownExport,
        onExit = onBack,
    )
    fun resetEdits() {
        crop = CropFractions()
        cropPreset = CropAspectPreset.FREE
        rotation = 0
        removeAudio = false
        reverseVideo = false
        trimStart = 0L
        trimEnd = fullDuration
        textLayers = emptyList()
        showTextResult = false
    }

    fun exportSpec(): VideoEditSpec = VideoEditSpec(
        startMs = trimStart,
        endMs = trimEnd.takeIf { end -> end > 0 && end < (info?.durationMs ?: Long.MAX_VALUE) },
        cropLeft = crop.left,
        cropRight = crop.right,
        cropTop = crop.top,
        cropBottom = crop.bottom,
        rotationDegrees = rotation,
        removeAudio = removeAudio,
        reverse = reverseVideo,
        textLayers = textLayers.mapNotNull { it.within(trimEnd - trimStart) },
    )

    LaunchedEffect(reverseVideo, trimStart, trimEnd, crop, rotation, removeAudio, textLayers) {
        reversePreviewJob?.cancel()
        reversePreviewJob = null
        reversePreviewFile?.delete()
        reversePreviewFile = null
        reversePreviewPercent = 0
        reversePreviewBusy = false
        reversePreviewError = null
    }
    DisposableEffect(item.id) {
        onDispose {
            reversePreviewJob?.cancel()
            reversePreviewFile?.delete()
        }
    }

    val previewPlayer = remember(uri) {
        uri?.let { sourceUri ->
            ExoPlayer.Builder(context)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
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
    val textPreviewSpec = if (showTextResult) VideoEditSpec(startMs = trimStart,
        cropLeft = crop.left, cropRight = crop.right, cropTop = crop.top,
        cropBottom = crop.bottom, rotationDegrees = rotation,
        textLayers = textLayers.mapNotNull { it.within(trimEnd - trimStart) }) else null
    LaunchedEffect(previewPlayer, textPreviewSpec) {
        val effects = textPreviewSpec?.let {
            com.domenota.medialoader.data.editor.VideoEditor(context).previewEffects(it, timeOffsetMs = it.startMs)
        } ?: emptyList()
        previewPlayer?.setVideoEffects(effects)
        previewPlayer?.seekTo(previewPlayer.currentPosition)
    }

    LaunchedEffect(uri) {
        if (uri == null) return@LaunchedEffect
        info = withContext(Dispatchers.IO) { readVideoInfo(context, uri) }
        trimEnd = info?.durationMs ?: 0L
    }

    if (textEditorOpen && uri != null && trimEnd > trimStart) EditorTextDialog(
        initial = textLayers.mapNotNull { it.within(trimEnd - trimStart) },
        durationMs = trimEnd - trimStart,
        videoSource = TextEditorVideoSource(uri, VideoEditSpec(startMs = trimStart, endMs = trimEnd,
            cropLeft = crop.left, cropRight = crop.right, cropTop = crop.top, cropBottom = crop.bottom,
            rotationDegrees = rotation)),
        initialPositionMs = ((previewPlayer?.currentPosition ?: trimStart) - trimStart).coerceAtLeast(0L),
        loadPreview = { position -> TextLayerRenderer.videoPreview(context, uri, trimStart + position, crop, rotation) },
        onApply = { textLayers = it; textEditorOpen = false; showTextResult = it.isNotEmpty() },
        onDismiss = { textEditorOpen = false },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Редактор видео") },
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
            if (textLayers.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showTextResult, onClick = { showTextResult = false }, label = { Text("Кадрирование") })
                FilterChip(selected = showTextResult, onClick = { showTextResult = true }, label = { Text("Итог с текстом") })
            }
            val previewWidth = ((info?.width ?: 16) * if (showTextResult) crop.keptWidth else 1f).roundToInt().coerceAtLeast(1)
            val previewHeight = ((info?.height ?: 9) * if (showTextResult) crop.keptHeight else 1f).roundToInt().coerceAtLeast(1)
            val previewRotated = showTextResult && rotation % 180 != 0
            CropVideoPreview(
                player = requireNotNull(previewPlayer),
                sourceWidth = if (previewRotated) previewHeight else previewWidth,
                sourceHeight = if (previewRotated) previewWidth else previewHeight,
                crop = crop,
                cropPreset = cropPreset,
                onCropChange = { crop = it },
                showCropFrame = !showTextResult,
            )

            Spacer(Modifier.height(18.dp))
            Button(onClick = { previewPlayer?.pause(); textEditorOpen = true },
                enabled = info != null && !ownExport && !busyWithOther,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (textLayers.isEmpty()) "Добавить текст" else "Текст · ${textLayers.size} слоёв — просмотреть и изменить")
            }
            Spacer(Modifier.height(18.dp))
            Text("Кадрирование", style = MaterialTheme.typography.titleMedium)
            Text(
                "Потяни сторону или угол рамки. Затемнённая область будет удалена.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CropAspectPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = cropPreset == preset,
                        onClick = {
                            cropPreset = preset
                            val dimensions = info
                            if (dimensions != null && preset != CropAspectPreset.FREE) {
                                crop = VideoCropMath.centerCrop(
                                    dimensions.width,
                                    dimensions.height,
                                    preset,
                                )
                            }
                        },
                        label = { Text(preset.label) },
                    )
                }
            }

            val dimensions = info
            if (dimensions != null) {
                Spacer(Modifier.height(10.dp))
                val outputWidth = (dimensions.width * crop.keptWidth).roundToInt().coerceAtLeast(1)
                val outputHeight = (dimensions.height * crop.keptHeight).roundToInt().coerceAtLeast(1)
                val rotated = rotation % 180 != 0
                Text(
                    "Итог: ${if (rotated) outputHeight else outputWidth} × " +
                        "${if (rotated) outputWidth else outputHeight} px",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            Spacer(Modifier.height(22.dp))
            Text("Обрезка по времени", style = MaterialTheme.typography.titleMedium)
            if ((info?.durationMs ?: 0L) > 0L) {
                MediaTrimControls(
                    player = requireNotNull(previewPlayer),
                    durationMs = info!!.durationMs,
                    trimStartMs = trimStart,
                    trimEndMs = trimEnd,
                    onTrimChange = { start, end ->
                        trimStart = start
                        trimEnd = end
                    },
                    modifier = Modifier.fillMaxWidth(),
                    showSolidTrimBar = false,
                    snapIntervalMs = info!!.frameDurationMs,
                    timelineVisual = { visualModifier ->
                        VideoTimelineStrip(
                            uri = uri,
                            durationMs = info!!.durationMs,
                            modifier = visualModifier,
                        )
                    },
                    scrubPreview = { positionMs ->
                        VideoScrubFrame(
                            uri = uri,
                            positionMs = positionMs,
                        )
                    },
                )
                Text(
                    "Тап по полосе запускает предпросмотр с выбранной точки до правой границы.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("Определяем длительность…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(22.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Поворот", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (rotation == 0) "Без поворота" else "${rotation}°",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { rotation = (rotation + 90) % 360 }) {
                    Icon(Icons.Rounded.RotateRight, contentDescription = null)
                    Text("  Повернуть")
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.VolumeOff, contentDescription = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("Убрать звук", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Видео сохранится без аудиодорожки",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(checked = removeAudio, onCheckedChange = { removeAudio = it })
            }

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FastRewind, contentDescription = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("Обратное воспроизведение", style = MaterialTheme.typography.titleMedium)
                        Text("С конца к началу. Звук также будет обращён, если не отключён.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Switch(checked = reverseVideo, onCheckedChange = { reverseVideo = it })
            }
            if (reverseVideo) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        if (reversePreviewFile == null) {
                        previewPlayer?.pause()
                        reversePreviewJob = previewScope.launch {
                            reversePreviewBusy = true
                            reversePreviewError = null
                            reversePreviewPercent = 0
                            try {
                                val file = VideoEditor(context).export(uri, exportSpec()) {
                                    reversePreviewPercent = it
                                }
                                currentCoroutineContext().ensureActive()
                                reversePreviewFile = file
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                reversePreviewError = error.message ?: "Не удалось создать предпросмотр реверса"
                            } finally {
                                reversePreviewBusy = false
                            }
                        }
                        }
                    },
                    enabled = info != null && !reversePreviewBusy && !ownExport && !busyWithOther,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Rounded.FastRewind, contentDescription = null)
                    Text("  Посмотреть в обратном порядке")
                }
                if (reversePreviewBusy) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { reversePreviewPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { reversePreviewJob?.cancel() }) { Text("Отменить предпросмотр") }
                }
                reversePreviewError?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(24.dp))
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
                        onExport(exportSpec())
                    },
                    enabled = info != null && !busyWithOther && !reversePreviewBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Text("Сохранить копию")
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        previewPlayer?.pause()
                        val positionMs = VideoFramePosition.clamp(
                            previewPlayer?.currentPosition ?: trimStart,
                            trimStart, trimEnd, fullDuration,
                        )
                        onSaveFrame(positionMs, VideoEditSpec(
                            startMs = trimStart,
                            cropLeft = crop.left,
                            cropRight = crop.right,
                            cropTop = crop.top,
                            cropBottom = crop.bottom,
                            rotationDegrees = rotation,
                            textLayers = textLayers.mapNotNull { it.within(trimEnd - trimStart) },
                        ))
                    },
                    enabled = info != null && !busyWithOther,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Icon(Icons.Rounded.PhotoCamera, contentDescription = null)
                    Text("  Сохранить кадр")
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        onExtractAudio(
                            trimStart,
                            trimEnd.takeIf { end ->
                                end > 0 && end < (info?.durationMs ?: Long.MAX_VALUE)
                            },
                        )
                    },
                    enabled = info != null && !busyWithOther,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Icon(Icons.Rounded.Audiotrack, contentDescription = null)
                    Text("  Извлечь аудио")
                }
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
    reversePreviewFile?.let { file ->
        ReverseVideoPreviewDialog(file) {
            reversePreviewFile = null
            file.delete()
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ReverseVideoPreviewDialog(file: File, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Предпросмотр реверса") },
        text = {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        this.player = player
                        useController = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxWidth().height(260.dp),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun CropVideoPreview(
    player: ExoPlayer,
    sourceWidth: Int,
    sourceHeight: Int,
    crop: CropFractions,
    cropPreset: CropAspectPreset,
    onCropChange: (CropFractions) -> Unit,
    showCropFrame: Boolean = true,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(
                (sourceWidth.toFloat() / sourceHeight.coerceAtLeast(1))
                    .coerceIn(0.35f, 2.4f),
            )
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = false
                    isClickable = false
                    isFocusable = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )
        if (showCropFrame) CropFrameOverlay(
            crop = crop,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            preset = cropPreset,
            onCropChange = onCropChange,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun readVideoInfo(context: android.content.Context, uri: Uri): VideoInfo? =
    runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val encodedWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: return@runCatching null
            val encodedHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: return@runCatching null
            val sourceRotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val (width, height) = if (sourceRotation % 180 != 0) {
                encodedHeight to encodedWidth
            } else {
                encodedWidth to encodedHeight
            }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val captureFrameRate =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toDoubleOrNull()
                    ?.takeIf { it in 1.0..240.0 }
            val frameCount =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
            val frameDurationMs = when {
                captureFrameRate != null -> (1_000.0 / captureFrameRate).toLong().coerceAtLeast(1L)
                frameCount != null && duration > 0L ->
                    (duration.toDouble() / frameCount).toLong().coerceAtLeast(1L)
                else -> null
            }
            VideoInfo(width, height, duration, frameDurationMs)
        } finally {
            retriever.release()
        }
    }.getOrNull()


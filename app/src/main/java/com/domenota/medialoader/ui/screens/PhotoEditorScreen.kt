package com.domenota.medialoader.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.FlipToFront
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.editor.CropAspectPreset
import com.domenota.medialoader.data.editor.CropFractions
import com.domenota.medialoader.data.editor.EditorExportState
import com.domenota.medialoader.data.editor.PhotoEditSpec
import com.domenota.medialoader.data.editor.MaskStroke
import com.domenota.medialoader.data.editor.VideoCropMath
import com.domenota.medialoader.ui.components.CropFrameOverlay
import com.domenota.medialoader.ui.components.EditorTextDialog
import com.domenota.medialoader.data.editor.TextLayer
import com.domenota.medialoader.data.editor.TextLayerRenderer
import com.domenota.medialoader.ui.components.rememberEditorExitRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class PhotoSourceInfo(
    val width: Int,
    val height: Int,
    val preview: Bitmap,
)

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoEditorScreen(
    item: DownloadEntity,
    exportState: EditorExportState,
    onBack: () -> Unit,
    onExport: (PhotoEditSpec) -> Unit,
    onCancelExport: () -> Unit,
) {
    val context = LocalContext.current
    val uri = item.savedUri?.let(Uri::parse)
    val ownExport = exportState.exporting && exportState.sourceId == item.id
    val busyWithOther = exportState.exporting && exportState.sourceId != null &&
        exportState.sourceId != item.id
    var sourceInfo by remember(uri) { mutableStateOf<PhotoSourceInfo?>(null) }
    var originalInfo by remember(uri) { mutableStateOf<PhotoSourceInfo?>(null) }
    var removeObjects by remember(item.id) { mutableStateOf(false) }
    var overlayPath by remember(item.id) { mutableStateOf<String?>(null) }
    var appliedMasks by remember(item.id) { mutableStateOf<List<List<MaskStroke>>>(emptyList()) }
    var crop by remember(item.id) { mutableStateOf(CropFractions()) }
    var cropPreset by remember(item.id) { mutableStateOf(CropAspectPreset.FREE) }
    var rotation by remember(item.id) { mutableIntStateOf(0) }
    var mirrorHorizontal by remember(item.id) { mutableStateOf(false) }
    var mirrorVertical by remember(item.id) { mutableStateOf(false) }
    var textLayers by remember(item.id) { mutableStateOf<List<TextLayer>>(emptyList()) }
    var textEditorOpen by remember(item.id) { mutableStateOf(false) }
    var showTextResult by remember(item.id) { mutableStateOf(false) }
    var textResultPreview by remember(item.id) { mutableStateOf<Bitmap?>(null) }

    val hasUnsavedChanges = crop != CropFractions() ||
        cropPreset != CropAspectPreset.FREE ||
        rotation != 0 || mirrorHorizontal || mirrorVertical || overlayPath != null || textLayers.isNotEmpty()
    val requestExit = rememberEditorExitRequest(
        hasUnsavedChanges = hasUnsavedChanges && !ownExport,
        onExit = onBack,
    )
    fun resetEdits() {
        crop = CropFractions()
        cropPreset = CropAspectPreset.FREE
        rotation = 0
        mirrorHorizontal = false
        mirrorVertical = false
        textLayers = emptyList()
        showTextResult = false
        if (!ownExport) overlayPath?.let { runCatching { java.io.File(it).delete() } }
        overlayPath = null
        appliedMasks = emptyList()
        originalInfo?.let { sourceInfo = it }
    }

    LaunchedEffect(uri) {
        if (uri == null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { readPhotoSource(context, uri) }
        originalInfo = loaded
        sourceInfo = loaded
    }

    if (textEditorOpen && sourceInfo != null) EditorTextDialog(
        initial = textLayers,
        loadPreview = { withContext(Dispatchers.Default) {
            TextLayerRenderer.preview(requireNotNull(sourceInfo).preview, crop, rotation, mirrorHorizontal, mirrorVertical)
        } },
        onApply = { textLayers = it; textEditorOpen = false; showTextResult = it.isNotEmpty() },
        onDismiss = { textEditorOpen = false },
    )
    LaunchedEffect(sourceInfo, crop, rotation, mirrorHorizontal, mirrorVertical, textLayers, showTextResult) {
        val source = sourceInfo?.preview
        if (showTextResult && source != null) {
            textResultPreview = withContext(Dispatchers.Default) {
                val transformed = TextLayerRenderer.preview(source, crop, rotation, mirrorHorizontal, mirrorVertical)
                val rendered = transformed.copy(Bitmap.Config.ARGB_8888, true)
                transformed.recycle()
                TextLayerRenderer.draw(context, android.graphics.Canvas(rendered), rendered.width,
                    rendered.height, textLayers, includeAll = true)
                rendered
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Редактор фото") },
                navigationIcon = {
                    IconButton(onClick = { if (removeObjects) removeObjects = false else requestExit() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (!removeObjects) TextButton(
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

        if (removeObjects && sourceInfo != null) {
            ObjectRemovalPanel(
                modifier = Modifier.padding(padding),
                originalWorkingImage = requireNotNull(sourceInfo).preview,
                earlierMasks = appliedMasks,
                onAccept = { bitmap, masks, path ->
                    if (!ownExport) {
                        overlayPath?.let { runCatching { java.io.File(it).delete() } }
                        overlayPath = path
                        appliedMasks = masks
                        sourceInfo = sourceInfo?.copy(preview = bitmap)
                        removeObjects = false
                    }
                },
                onClose = { removeObjects = false },
            )
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
            val info = sourceInfo
            if (info == null) {
                Box(
                    Modifier.fillMaxWidth().height(280.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Открываем изображение…")
                }
            } else if (showTextResult && textResultPreview != null) {
                val rendered = requireNotNull(textResultPreview)
                Image(rendered.asImageBitmap(), contentDescription = "Итог с текстом",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp), contentScale = ContentScale.Fit)
            } else {
                PhotoCropPreview(
                    info = info,
                    crop = crop,
                    cropPreset = cropPreset,
                    rotation = rotation,
                    mirrorHorizontal = mirrorHorizontal,
                    mirrorVertical = mirrorVertical,
                    onCropChange = { crop = it },
                )
            }

            Spacer(Modifier.height(14.dp))
            Button(onClick = { textEditorOpen = true },
                enabled = sourceInfo != null && !ownExport && !busyWithOther,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (textLayers.isEmpty()) "Добавить текст" else "Текст · ${textLayers.size} слоёв — просмотреть и изменить")
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { removeObjects = true },
                enabled = sourceInfo != null && !ownExport && !busyWithOther,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Удалить объект с фото · LaMa") }
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
                            sourceInfo?.let { source ->
                                if (preset != CropAspectPreset.FREE) {
                                    crop = VideoCropMath.centerCrop(
                                        source.width,
                                        source.height,
                                        preset,
                                    )
                                }
                            }
                        },
                        label = { Text(preset.label) },
                    )
                }
            }

            sourceInfo?.let { source ->
                Spacer(Modifier.height(10.dp))
                val outWidth = (source.width * crop.keptWidth).roundToInt().coerceAtLeast(1)
                val outHeight = (source.height * crop.keptHeight).roundToInt().coerceAtLeast(1)
                val rotated = rotation % 180 != 0
                Text(
                    "Итог: ${if (rotated) outHeight else outWidth} × ${if (rotated) outWidth else outHeight} px",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            Spacer(Modifier.height(22.dp))
            Text("Поворот и отражение", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = rotation != 0,
                    onClick = { rotation = (rotation + 90) % 360 },
                    label = { Text(if (rotation == 0) "Повернуть 90°" else "Поворот $rotation°") },
                    leadingIcon = { Icon(Icons.Rounded.RotateRight, contentDescription = null) },
                )
                FilterChip(
                    selected = mirrorHorizontal,
                    onClick = { mirrorHorizontal = !mirrorHorizontal },
                    label = { Text("По горизонтали") },
                    leadingIcon = { Icon(Icons.Rounded.Flip, contentDescription = null) },
                )
                FilterChip(
                    selected = mirrorVertical,
                    onClick = { mirrorVertical = !mirrorVertical },
                    label = { Text("По вертикали") },
                    leadingIcon = { Icon(Icons.Rounded.FlipToFront, contentDescription = null) },
                )
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
                            PhotoEditSpec(
                                cropLeft = crop.left,
                                cropRight = crop.right,
                                cropTop = crop.top,
                                cropBottom = crop.bottom,
                                rotationDegrees = rotation,
                                mirrorHorizontal = mirrorHorizontal,
                                mirrorVertical = mirrorVertical,
                                inpaintOverlayPath = overlayPath,
                                textLayers = textLayers,
                            ),
                        )
                    },
                    enabled = sourceInfo != null && !busyWithOther,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Text("Сохранить копию")
                }
                Text(
                    "Исходное фото не изменяется.",
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

@Composable
private fun PhotoCropPreview(
    info: PhotoSourceInfo,
    crop: CropFractions,
    cropPreset: CropAspectPreset,
    rotation: Int,
    mirrorHorizontal: Boolean,
    mirrorVertical: Boolean,
    onCropChange: (CropFractions) -> Unit,
) {
    var showOriginal by remember(info.preview) { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio((info.width.toFloat() / info.height.coerceAtLeast(1)).coerceIn(0.35f, 2.4f))
            .background(Color.Black),
    ) {
        Image(
            bitmap = info.preview.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = if (showOriginal) 0f else rotation.toFloat()
                    scaleX = if (!showOriginal && mirrorHorizontal) -1f else 1f
                    scaleY = if (!showOriginal && mirrorVertical) -1f else 1f
                },
        )
        CropFrameOverlay(
            crop = crop,
            sourceWidth = info.width,
            sourceHeight = info.height,
            preset = cropPreset,
            onCropChange = onCropChange,
            modifier = Modifier.fillMaxSize(),
            showFrame = !showOriginal,
            onCompareOriginalChange = { showOriginal = it },
        )
        if (showOriginal) {
            Text(
                "Оригинал",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.62f), MaterialTheme.shapes.small)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

private fun readPhotoSource(context: android.content.Context, uri: Uri): PhotoSourceInfo? =
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        val preview = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } ?: return@runCatching null
        PhotoSourceInfo(bounds.outWidth, bounds.outHeight, preview)
    }.getOrNull()

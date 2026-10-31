package com.domenota.medialoader.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.editor.*
import com.domenota.medialoader.ui.components.CropFrameOverlay
import com.domenota.medialoader.ui.components.EditorTextDialog
import com.domenota.medialoader.ui.components.rememberEditorExitRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class PhotoSourceInfo(val width: Int, val height: Int, val preview: Bitmap)

private enum class PhotoWorkspaceTab(val title: String) {
    CROP("Обрезка"), LEVELS("Уровни"), HSL("HSL"), EFFECTS("Эффекты"),
    TEXT("Надпись"), WATERMARK("Водяной знак"), AI("AI")
}
private enum class PhotoLevelControl(val title: String) {
    EXPOSURE("Выдержка"), BRIGHTNESS("Яркость"), CONTRAST("Контраст"),
    SATURATION("Насыщенность"), WARMTH("Теплота"),
    SHADOWS("Тени"), HIGHLIGHTS("Светлые участки"),
    BLACK_POINT("Точка чёрного"), CURVE("Гамма")
}

private enum class PhotoHslControl(val title: String) {
    HUE("Оттенок"), SATURATION("Насыщенность"), LIGHTNESS("Светлота")
}
private data class PhotoEditSnapshot(
    val crop: CropFractions = CropFractions(),
    val cropPreset: CropAspectPreset = CropAspectPreset.FREE,
    val rotation: Int = 0,
    val mirrorHorizontal: Boolean = false,
    val mirrorVertical: Boolean = false,
    val color: PhotoColorAdjustments = PhotoColorAdjustments(),
    val textLayers: List<TextLayer> = emptyList(),
    val drawing: List<PhotoMark> = emptyList(),
    val graphicWatermark: PhotoGraphicWatermark? = null,
    val overlayPath: String? = null,
    val appliedMasks: List<List<MaskStroke>> = emptyList(),
    val inpaintPreview: Bitmap? = null,
)

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
    val busyWithOther = exportState.exporting && exportState.sourceId != null && exportState.sourceId != item.id
    val locked = ownExport || busyWithOther
    var originalInfo by remember(uri) { mutableStateOf<PhotoSourceInfo?>(null) }
    var edit by remember(item.id) { mutableStateOf(PhotoEditSnapshot()) }
    val undoHistory = remember(item.id) { mutableStateListOf<PhotoEditSnapshot>() }
    val redoHistory = remember(item.id) { mutableStateListOf<PhotoEditSnapshot>() }
    var pendingGesture by remember(item.id) { mutableStateOf<PhotoEditSnapshot?>(null) }
    var textEditorOpen by remember(item.id) { mutableStateOf(false) }
    var drawingEditorOpen by remember(item.id) { mutableStateOf(false) }
    var removeObjects by remember(item.id) { mutableStateOf(false) }
    var currentTab by remember(item.id) { mutableStateOf(PhotoWorkspaceTab.CROP) }
    var activeLevel by remember(item.id) { mutableStateOf(PhotoLevelControl.BRIGHTNESS) }
    var activeHslRange by remember(item.id) { mutableStateOf(HslRange.RED) }
    var activeHslControl by remember(item.id) { mutableStateOf(PhotoHslControl.HUE) }
    var cropTool by remember(item.id) { mutableStateOf("ratio") }
    var menuOpen by remember { mutableStateOf(false) }
    var composedPreview by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    var cropColorPreview by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    val photoViewport = remember(item.id) { PhotoViewportState() }
    var previewError by remember(item.id) { mutableStateOf<String?>(null) }

    fun commit(next: PhotoEditSnapshot) {
        if (locked || edit == next) return
        if (undoHistory.size >= 32) undoHistory.removeAt(0)
        undoHistory.add(edit)
        edit = next
        redoHistory.clear()
    }
    fun finishGesture() {
        pendingGesture?.let { before ->
            if (before != edit) {
                if (undoHistory.size >= 32) undoHistory.removeAt(0)
                undoHistory.add(before)
                redoHistory.clear()
            }
        }
        pendingGesture = null
    }
    fun undo() {
        if (!locked && undoHistory.isNotEmpty()) {
            redoHistory.add(edit)
            edit = undoHistory.removeAt(undoHistory.lastIndex)
        }
    }
    fun redo() {
        if (!locked && redoHistory.isNotEmpty()) {
            undoHistory.add(edit)
            edit = redoHistory.removeAt(redoHistory.lastIndex)
        }
    }
    fun reset() {
        if (!locked) {
            commit(PhotoEditSnapshot())
            pendingGesture = null
        }
    }

    val graphicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if (selected != null && !locked) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    selected, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            commit(edit.copy(graphicWatermark = PhotoGraphicWatermark(uri = selected.toString())))
        }
    }

    val info = remember(originalInfo, edit.inpaintPreview) {
        originalInfo?.let { base ->
            edit.inpaintPreview?.let { base.copy(preview = it) } ?: base
        }
    }
    val dirty = edit != PhotoEditSnapshot()
    val requestExit = rememberEditorExitRequest(
        hasUnsavedChanges = dirty && !ownExport, onExit = onBack
    )
    LaunchedEffect(uri) {
        if (uri != null) {
            originalInfo = withContext(Dispatchers.IO) { readPhotoSource(context, uri) }
        }
    }

    // Pixel previews are ownership-managed; don't let repeated slider gestures retain bitmaps.
    DisposableEffect(composedPreview) {
        val old = composedPreview
        onDispose { if (old != null && !old.isRecycled) old.recycle() }
    }
    DisposableEffect(cropColorPreview, info?.preview) {
        val previous = cropColorPreview
        val original = info?.preview
        onDispose {
            if (previous != null && previous !== original && !previous.isRecycled) previous.recycle()
        }
    }
    LaunchedEffect(info, edit.color) {
        val source = info?.preview ?: return@LaunchedEffect
        delay(100L)
        cropColorPreview = withContext(Dispatchers.IO) {
            PhotoColorMath.apply(source, edit.color)
        }
    }
    LaunchedEffect(edit.crop, edit.rotation, edit.mirrorHorizontal, edit.mirrorVertical) {
        photoViewport.reset()
    }

    // Only bake the graphic mark outside its editing tab. While dragging, its
    // overlay moves independently without decoding/recompositing the full bitmap.
    val bakedGraphic = if (currentTab == PhotoWorkspaceTab.WATERMARK)
        null else edit.graphicWatermark

    // All non-crop tabs show the *actual* edited geometry, color and text.
    // Debounced previews are calculated away from the UI thread; full-resolution
    // export applies the same operations in the same order.
    LaunchedEffect(info, edit.crop, edit.rotation, edit.mirrorHorizontal, edit.mirrorVertical,
        edit.color, edit.textLayers, edit.drawing, bakedGraphic, currentTab) {
        val source = info?.preview
        if (source == null) { composedPreview = null; return@LaunchedEffect }
        delay(100L)
        try {
            val rendered = withContext(Dispatchers.IO) {
                val transformed = TextLayerRenderer.preview(source, edit.crop, edit.rotation,
                    edit.mirrorHorizontal, edit.mirrorVertical)
                val colored = try { PhotoColorMath.apply(transformed, edit.color) }
                    catch (error: Throwable) { transformed.recycle(); throw error }
                // In the watermark tab its graphic is a separate interactive layer,
                // avoiding expensive bitmap recompositing on every finger movement.
                val hasOverlays = bakedGraphic != null ||
                    edit.drawing.isNotEmpty() || edit.textLayers.isNotEmpty()
                val output = if (hasOverlays) colored.copy(Bitmap.Config.ARGB_8888, true)
                    else colored
                try {
                    if (hasOverlays) {
                        val canvas = android.graphics.Canvas(output)
                        bakedGraphic?.let {
                            PhotoGraphicWatermarkRenderer.draw(context, canvas, output.width, output.height, it)
                        }
                        if (edit.drawing.isNotEmpty())
                            PhotoDrawing.draw(canvas, output.width, output.height, edit.drawing)
                        if (edit.textLayers.isNotEmpty())
                            TextLayerRenderer.draw(context, canvas, output.width, output.height,
                                edit.textLayers, includeAll = true)
                    }
                    output
                } catch (error: Throwable) {
                    if (output !== colored) output.recycle()
                    else if (colored !== transformed) colored.recycle()
                    throw error
                } finally {
                    // Never recycle the bitmap returned to Compose.
                    if (colored !== output && !colored.isRecycled) colored.recycle()
                    if (transformed !== output && transformed !== colored && !transformed.isRecycled)
                        transformed.recycle()
                }
            }
            composedPreview = rendered
            previewError = null
        } catch (error: Exception) {
            previewError = "Не удалось открыть водяной знак: ${error.message.orEmpty()}"
        }
    }

    if (textEditorOpen && info != null) {
        EditorTextDialog(
            initial = edit.textLayers,
            loadPreview = { _: Long -> withContext(Dispatchers.Default) {
                val transformed = TextLayerRenderer.preview(info.preview, edit.crop, edit.rotation,
                    edit.mirrorHorizontal, edit.mirrorVertical)
                val colored = PhotoColorMath.apply(transformed, edit.color)
                if (colored !== transformed) transformed.recycle()
                colored
            } },
            onApply = { layers -> commit(edit.copy(textLayers = layers)); textEditorOpen = false },
            onDismiss = { textEditorOpen = false },
        )
    }

    if (drawingEditorOpen && composedPreview != null) {
        PhotoDrawDialog(
            preview = requireNotNull(composedPreview),
            onApply = { marks ->
                if (marks.isNotEmpty()) commit(edit.copy(drawing = edit.drawing + marks))
                drawingEditorOpen = false
            },
            onDismiss = { drawingEditorOpen = false },
        )
    }

    Scaffold(
        containerColor = Color.Black,
        contentColor = Color.White,
        topBar = {
            Row(Modifier.fillMaxWidth().background(Color.Black).padding(horizontal = 7.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (removeObjects) removeObjects = false else requestExit() }) {
                    Icon(Icons.Rounded.Close, "Закрыть редактор", tint = Color.White)
                }
                IconButton(onClick = ::undo, enabled = !locked && undoHistory.isNotEmpty() && !removeObjects) {
                    Icon(Icons.Rounded.Undo, "Отменить", tint = if (undoHistory.isNotEmpty()) Color.White else Color.Gray)
                }
                IconButton(onClick = ::redo, enabled = !locked && redoHistory.isNotEmpty() && !removeObjects) {
                    Icon(Icons.Rounded.Redo, "Повторить", tint = if (redoHistory.isNotEmpty()) Color.White else Color.Gray)
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        onExport(PhotoEditSpec(
                            cropLeft = edit.crop.left, cropRight = edit.crop.right,
                            cropTop = edit.crop.top, cropBottom = edit.crop.bottom,
                            rotationDegrees = edit.rotation,
                            mirrorHorizontal = edit.mirrorHorizontal, mirrorVertical = edit.mirrorVertical,
                            inpaintOverlayPath = edit.overlayPath, textLayers = edit.textLayers,
                            colorAdjustments = edit.color,
                            drawing = edit.drawing,
                            graphicWatermark = edit.graphicWatermark,
                        ))
                    },
                    enabled = dirty && info != null && !locked && !removeObjects,
                ) { Text("Сохранить", color = if (dirty && !locked) Color.White else Color.Gray) }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, "Дополнительно", tint = Color.White)
                    }
                    DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Сбросить изменения") },
                            enabled = dirty && !locked,
                            onClick = { menuOpen = false; reset() },
                            leadingIcon = { Icon(Icons.Rounded.RestartAlt, null) })
                        DropdownMenuItem(text = { Text("Исходник сохранится без изменений") },
                            onClick = { menuOpen = false },
                            leadingIcon = { Icon(Icons.Rounded.Info, null) })
                    }
                }
            }
        },
    ) { padding ->
        if (uri == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Файл недоступен")
            }
            return@Scaffold
        }
        if (removeObjects && info != null) {
            ObjectRemovalPanel(
                modifier = Modifier.fillMaxSize().padding(padding),
                originalWorkingImage = info.preview,
                earlierMasks = edit.appliedMasks,
                onAccept = { bitmap, masks, path ->
                    if (!locked) {
                        commit(edit.copy(overlayPath = path, appliedMasks = masks, inpaintPreview = bitmap))
                        removeObjects = false
                    }
                },
                onClose = { removeObjects = false },
            )
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).background(Color.Black)) {
            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center) {
                if (info == null) {
                    Text("Открываем изображение…", color = Color.LightGray)
                } else if (currentTab != PhotoWorkspaceTab.CROP &&
                    composedPreview != null) {
                    PhotoZoomViewport(
                        bitmap = requireNotNull(composedPreview), state = photoViewport,
                        graphic = if (currentTab == PhotoWorkspaceTab.WATERMARK)
                            edit.graphicWatermark else null,
                        onMoveGraphic = { dx, dy ->
                            val mark = edit.graphicWatermark
                            if (mark != null && !locked) {
                                if (pendingGesture == null) pendingGesture = edit
                                edit = edit.copy(graphicWatermark = mark.copy(
                                    centerX = (mark.centerX + dx).coerceIn(0f, 1f),
                                    centerY = (mark.centerY + dy).coerceIn(0f, 1f)))
                            }
                        },
                        onGraphicDragEnd = ::finishGesture,
                    )
                } else {
                    PhotoWorkspacePreview(
                        info = info, edit = edit, colorPreview = cropColorPreview,
                        cropFrameVisible = currentTab == PhotoWorkspaceTab.CROP && !locked,
                        onCropChange = {
                            if (pendingGesture == null) pendingGesture = edit
                            edit = edit.copy(crop = it)
                        },
                        onCropFinished = ::finishGesture,
                    )
                }
            }
            if (ownExport) {
                Text(exportState.operation + " · " + exportState.progress + "%",
                    modifier = Modifier.padding(horizontal = 16.dp))
                LinearProgressIndicator(progress = { exportState.progress / 100f },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                TextButton(onClick = onCancelExport) { Text("Отменить обработку") }
            } else if (busyWithOther) {
                Text("Обрабатывается другой файл. Редактирование временно недоступно.",
                    modifier = Modifier.padding(12.dp), color = Color.LightGray)
            }
            if (!removeObjects) {
                PhotoWorkspaceTools(
                    tab = currentTab, onTab = { currentTab = it },
                    cropTool = cropTool, onCropTool = { cropTool = it },
                    edit = edit, info = info, locked = locked,
                    activeLevel = activeLevel, onActiveLevel = { activeLevel = it },
                    activeHslRange = activeHslRange, onHslRange = { activeHslRange = it },
                    activeHslControl = activeHslControl, onHslControl = { activeHslControl = it },
                    onEdit = ::commit,
                    onLiveEdit = { update ->
                        if (pendingGesture == null) pendingGesture = edit
                        edit = update
                    },
                    onEditFinished = ::finishGesture,
                    onText = { textEditorOpen = true },
                    onDraw = { drawingEditorOpen = true },
                    onPickGraphic = { graphicPicker.launch(arrayOf("image/png", "image/jpeg", "image/webp")) },
                    onLaMa = { removeObjects = true },
                )
            }
            previewError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp)) }
            exportState.message
                ?.takeIf { exportState.sourceId == null || exportState.sourceId == item.id }
                ?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) }
        }
    }
}

@Composable
private fun PhotoWorkspacePreview(
    info: PhotoSourceInfo, edit: PhotoEditSnapshot, colorPreview: Bitmap?,
    cropFrameVisible: Boolean, onCropChange: (CropFractions) -> Unit,
    onCropFinished: () -> Unit,
) {
    var showOriginal by remember(info.preview) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val displayed = if (showOriginal) info.preview else colorPreview ?: info.preview
        Image(displayed.asImageBitmap(), null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                rotationZ = if (showOriginal) 0f else edit.rotation.toFloat()
                scaleX = if (!showOriginal && edit.mirrorHorizontal) -1f else 1f
                scaleY = if (!showOriginal && edit.mirrorVertical) -1f else 1f
            })
        if (cropFrameVisible) {
            CropFrameOverlay(
                crop = edit.crop, sourceWidth = info.width, sourceHeight = info.height,
                preset = edit.cropPreset, onCropChange = onCropChange,
                modifier = Modifier.fillMaxSize(),
                showFrame = !showOriginal,
                onCompareOriginalChange = { showOriginal = it },
                onCropChangeFinished = onCropFinished,
            )
        }
    }
}

@Composable
private fun PhotoWorkspaceTools(
    tab: PhotoWorkspaceTab, onTab: (PhotoWorkspaceTab) -> Unit,
    cropTool: String, onCropTool: (String) -> Unit,
    edit: PhotoEditSnapshot, info: PhotoSourceInfo?, locked: Boolean,
    activeLevel: PhotoLevelControl, onActiveLevel: (PhotoLevelControl) -> Unit,
    activeHslRange: HslRange, onHslRange: (HslRange) -> Unit,
    activeHslControl: PhotoHslControl, onHslControl: (PhotoHslControl) -> Unit,
    onEdit: (PhotoEditSnapshot) -> Unit,
    onLiveEdit: (PhotoEditSnapshot) -> Unit,
    onEditFinished: () -> Unit,
    onText: () -> Unit, onDraw: () -> Unit,
    onPickGraphic: () -> Unit, onLaMa: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Color.Black)
        .height(if (tab == PhotoWorkspaceTab.HSL) 242.dp else 188.dp)) {
        Box(Modifier.fillMaxWidth()
            .height(if (tab == PhotoWorkspaceTab.HSL) 118.dp else 64.dp),
            contentAlignment = Alignment.Center) {
            when (tab) {
                PhotoWorkspaceTab.CROP -> {
                    if (cropTool == "ratio") Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CropAspectPreset.entries.forEach { preset ->
                            FilterChip(selected = edit.cropPreset == preset,
                                enabled = !locked && info != null,
                                onClick = {
                                    val crop = if (preset == CropAspectPreset.FREE) CropFractions()
                                        else info?.let {
                                            VideoCropMath.centerCrop(it.width, it.height, preset)
                                        } ?: CropFractions()
                                    onEdit(edit.copy(cropPreset = preset, crop = crop))
                                }, label = { Text(preset.label) })
                        }
                    } else Text(
                        if (cropTool == "rotate") "Поворот: " + edit.rotation + "°"
                        else "Нажми инструмент ещё раз для отражения",
                        fontSize = 13.sp, color = Color.LightGray)
                }
                PhotoWorkspaceTab.LEVELS -> {
                    val value = when(activeLevel) {
                        PhotoLevelControl.EXPOSURE -> edit.color.exposure
                        PhotoLevelControl.BRIGHTNESS -> edit.color.brightness
                        PhotoLevelControl.CONTRAST -> edit.color.contrast
                        PhotoLevelControl.SATURATION -> edit.color.saturation
                        PhotoLevelControl.WARMTH -> edit.color.warmth
                        PhotoLevelControl.SHADOWS -> edit.color.shadows
                        PhotoLevelControl.HIGHLIGHTS -> edit.color.highlights
                        PhotoLevelControl.BLACK_POINT -> edit.color.blackPoint
                        PhotoLevelControl.CURVE -> edit.color.curve
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(activeLevel.title, fontSize = 12.sp, color = Color.LightGray)
                            Text(value.roundToInt().toString(), fontSize = 12.sp, color = Color.LightGray)
                        }
                        Slider(
                            value = value, onValueChange = { next ->
                                val color = when(activeLevel) {
                                    PhotoLevelControl.EXPOSURE -> edit.color.copy(exposure = next)
                                    PhotoLevelControl.BRIGHTNESS -> edit.color.copy(brightness = next)
                                    PhotoLevelControl.CONTRAST -> edit.color.copy(contrast = next)
                                    PhotoLevelControl.SATURATION -> edit.color.copy(saturation = next)
                                    PhotoLevelControl.WARMTH -> edit.color.copy(warmth = next)
                                    PhotoLevelControl.SHADOWS -> edit.color.copy(shadows = next)
                                    PhotoLevelControl.HIGHLIGHTS -> edit.color.copy(highlights = next)
                                    PhotoLevelControl.BLACK_POINT -> edit.color.copy(blackPoint = next)
                                    PhotoLevelControl.CURVE -> edit.color.copy(curve = next)
                                }
                                onLiveEdit(edit.copy(color = color))
                            },
                            onValueChangeFinished = onEditFinished,
                            valueRange = -100f..100f, enabled = !locked && info != null,
                        )
                    }
                }
                PhotoWorkspaceTab.HSL -> {
                    val correction = edit.color.hsl[activeHslRange] ?: HslCorrection()
                    val value = when (activeHslControl) {
                        PhotoHslControl.HUE -> correction.hue
                        PhotoHslControl.SATURATION -> correction.saturation
                        PhotoHslControl.LIGHTNESS -> correction.lightness
                    }
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            HslRange.entries.forEach { range ->
                                FilterChip(selected = activeHslRange == range,
                                    enabled = !locked && info != null,
                                    onClick = { onHslRange(range) },
                                    label = { Text(range.label, fontSize = 11.sp) })
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(value.roundToInt().toString(), fontSize = 11.sp,
                                color = Color.LightGray, modifier = Modifier.width(32.dp))
                            Slider(value = value, valueRange = -100f..100f,
                                onValueChange = { next ->
                                    val updated = when(activeHslControl) {
                                        PhotoHslControl.HUE -> correction.copy(hue = next)
                                        PhotoHslControl.SATURATION -> correction.copy(saturation = next)
                                        PhotoHslControl.LIGHTNESS -> correction.copy(lightness = next)
                                    }
                                    val nextHsl = edit.color.hsl.toMutableMap()
                                    if (updated.isNeutral()) nextHsl.remove(activeHslRange)
                                    else nextHsl[activeHslRange] = updated
                                    onLiveEdit(edit.copy(color = edit.color.copy(hsl = nextHsl)))
                                }, onValueChangeFinished = onEditFinished,
                                enabled = !locked && info != null,
                                modifier = Modifier.weight(1f))
                        }
                    }
                }
                PhotoWorkspaceTab.EFFECTS -> {
                    Text("Выбери эффект — изменения сразу появятся на фотографии",
                        fontSize = 12.sp, color = Color.LightGray)
                }
                PhotoWorkspaceTab.TEXT ->
                    Text("Надписи, кисти и фигуры накладываются поверх снимка",
                        fontSize = 12.sp, color = Color.LightGray)
                PhotoWorkspaceTab.WATERMARK -> {
                    val mark = edit.graphicWatermark
                    if (mark == null) Text("Выбери изображение PNG/JPG/WebP для водяного знака",
                        fontSize = 12.sp, color = Color.LightGray)
                    else Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Размер", color = Color.White, fontSize = 10.sp)
                        Slider(value = mark.relativeWidth,
                            onValueChange = { onLiveEdit(edit.copy(graphicWatermark =
                                mark.copy(relativeWidth = it))) },
                            onValueChangeFinished = onEditFinished,
                            valueRange = .04f..0.90f, modifier = Modifier.weight(1f),
                            enabled = !locked)
                        Text("Непрозр.", color = Color.White, fontSize = 10.sp)
                        Slider(value = mark.opacity,
                            onValueChange = { onLiveEdit(edit.copy(graphicWatermark =
                                mark.copy(opacity = it))) },
                            onValueChangeFinished = onEditFinished,
                            valueRange = .05f..1f, modifier = Modifier.weight(1f),
                            enabled = !locked)
                    }
                }
                PhotoWorkspaceTab.AI ->
                    Text("LaMa · удаление объектов на устройстве",
                        fontSize = 12.sp, color = Color.LightGray)
            }
        }
        Row(Modifier.fillMaxWidth().height(76.dp).horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            when(tab) {
                PhotoWorkspaceTab.CROP -> {
                    PhotoToolCard("Соотношение", Icons.Rounded.Crop, cropTool == "ratio",
                        { onCropTool("ratio") }, !locked)
                    PhotoToolCard("Поворот", Icons.Rounded.RotateRight, false,
                        { onCropTool("rotate"); onEdit(edit.copy(rotation = (edit.rotation + 90) % 360)) }, !locked)
                    PhotoToolCard("Горизонтально", Icons.Rounded.Flip, false,
                        { onCropTool("horizontal"); onEdit(edit.copy(mirrorHorizontal = !edit.mirrorHorizontal)) }, !locked)
                    PhotoToolCard("Вертикально", Icons.Rounded.FlipToFront, false,
                        { onCropTool("vertical"); onEdit(edit.copy(mirrorVertical = !edit.mirrorVertical)) }, !locked)
                }
                PhotoWorkspaceTab.LEVELS -> PhotoLevelControl.entries.forEach { control ->
                    PhotoToolCard(control.title, Icons.Rounded.Tune, activeLevel == control,
                        { onActiveLevel(control) }, !locked)
                }
                PhotoWorkspaceTab.HSL -> PhotoHslControl.entries.forEach { control ->
                    PhotoToolCard(control.title, Icons.Rounded.Palette,
                        activeHslControl == control, { onHslControl(control) }, !locked)
                }
                PhotoWorkspaceTab.EFFECTS -> PhotoEffect.entries.forEach { effect ->
                    PhotoEffectCard(effect = effect, info = info, base = edit.color,
                        selected = edit.color.effect == effect,
                        enabled = !locked && info != null,
                        onClick = { onEdit(edit.copy(color = edit.color.copy(effect = effect))) })
                }
                PhotoWorkspaceTab.TEXT -> {
                    PhotoToolCard("Надпись", Icons.Rounded.TextFields,
                        false, onText, !locked && info != null)
                    PhotoToolCard("Кисть и фигуры", Icons.Rounded.Brush,
                        false, onDraw, !locked && info != null)
                }
                PhotoWorkspaceTab.WATERMARK -> {
                    PhotoToolCard("Графический знак", Icons.Rounded.AddPhotoAlternate,
                        false, onPickGraphic, !locked && info != null)
                    PhotoToolCard("Текстовый знак", Icons.Rounded.TextFields,
                        false, onText, !locked && info != null)
                    edit.graphicWatermark?.let { mark ->
                        PhotoToolCard("Слева", Icons.Rounded.ArrowBack, false,
                            { onEdit(edit.copy(graphicWatermark =
                                mark.copy(centerX = .18f, centerY = .84f))) }, !locked)
                        PhotoToolCard("Центр", Icons.Rounded.CenterFocusStrong, false,
                            { onEdit(edit.copy(graphicWatermark =
                                mark.copy(centerX = .5f, centerY = .5f))) }, !locked)
                        PhotoToolCard("Справа", Icons.Rounded.ArrowForward, false,
                            { onEdit(edit.copy(graphicWatermark =
                                mark.copy(centerX = .82f, centerY = .84f))) }, !locked)
                        PhotoToolCard("Убрать знак", Icons.Rounded.DeleteOutline, false,
                            { onEdit(edit.copy(graphicWatermark = null)) }, !locked)
                    }
                }
                PhotoWorkspaceTab.AI ->
                    PhotoToolCard("AI-ластик · LaMa", Icons.Rounded.AutoFixHigh,
                        false, onLaMa, !locked && info != null)
            }
        }
        Row(Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState())
            .padding(horizontal = 11.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            PhotoWorkspaceTab.entries.forEach { category ->
                FilterChip(selected = tab == category, onClick = { onTab(category) },
                    label = { Text(category.title, fontSize = 13.sp) })
            }
        }
    }
}

@Composable
private fun PhotoEffectCard(
    effect: PhotoEffect, info: PhotoSourceInfo?, base: PhotoColorAdjustments,
    selected: Boolean, enabled: Boolean, onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.width(91.dp).height(67.dp), onClick = onClick, enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF232328),
    ) {
        Box(Modifier.fillMaxSize().padding(3.dp).clip(RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.BottomCenter) {
            info?.let { source ->
                val adjusted = base.copy(effect = effect)
                Image(source.preview.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                    colorFilter = if (adjusted.isNeutral()) null
                    else ColorFilter.colorMatrix(ComposeColorMatrix(PhotoColorMath.matrix(adjusted).array)))
            }
            Box(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .55f)),
                contentAlignment = Alignment.Center) {
                Text(effect.label, fontSize = 11.sp, color = Color.White, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PhotoToolCard(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean, onClick: () -> Unit, enabled: Boolean) {
    Surface(onClick = onClick, enabled = enabled,
        modifier = Modifier.width(106.dp).height(67.dp),
        shape = RoundedCornerShape(15.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF242428)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 7.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, modifier = Modifier.size(22.dp),
                tint = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White)
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 11.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, lineHeight = 12.sp,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White)
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

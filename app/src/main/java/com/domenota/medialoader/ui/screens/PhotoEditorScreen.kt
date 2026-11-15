package com.domenota.medialoader.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import com.domenota.medialoader.ui.components.EditorSlider as Slider
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.editor.*
import com.domenota.medialoader.ui.components.CropFrameOverlay
import com.domenota.medialoader.ui.components.EditorTextDialog
import com.domenota.medialoader.ui.components.rememberEditorExitRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class PhotoSourceInfo(val width: Int, val height: Int, val preview: Bitmap)

private enum class PhotoWorkspaceTab(val title: String) {
    CROP("Обрезка"), LEVELS("Уровни"), EFFECTS("Эффекты"),
    TEXT("Надпись"), WATERMARK("Водяной знак"), AI("AI")
}
internal enum class PhotoLevelControl(val title: String) {
    EXPOSURE("Выдержка"), BRIGHTNESS("Яркость"), CONTRAST("Контраст"),
    SATURATION("Насыщенность"), VIBRANCE("Красочность"),
    WARMTH("Теплота"), TINT("Оттенок"),
    SHADOWS("Тени"), HIGHLIGHTS("Светлые участки"),
    BLACK_POINT("Точка чёрного"), CURVE("Гамма"),
    SHARPNESS("Резкость"), CLARITY("Чёткость"), RGB_CURVES("Кривые RGB"),
    HSL("HSL"), GLOW("Свечение"), VIGNETTE("Виньетка"), DISPERSION("Дисперсия"),
    POSTERIZATION("Постеризация"), GRAIN("Зернистость"),
    DENOISE("Устранение шума"), NOISE("Шум")
}

private enum class PhotoHslControl(val title: String) {
    HUE("Оттенок"), SATURATION("Насыщенность"), LIGHTNESS("Светлота")
}
internal data class PhotoEditSnapshot(
    val crop: CropFractions = CropFractions(),
    val cropPreset: CropAspectPreset = CropAspectPreset.FREE,
    val cropReferenceWidth: Int = 0,
    val cropReferenceHeight: Int = 0,
    val geometry: PhotoGeometry = PhotoGeometry(),
    val rotation: Int = 0,
    val mirrorHorizontal: Boolean = false,
    val mirrorVertical: Boolean = false,
    val color: PhotoColorAdjustments = PhotoColorAdjustments(),
    val textLayers: List<TextLayer> = emptyList(),
    val drawing: List<PhotoMark> = emptyList(),
    val localEffects: List<PhotoLocalStroke> = emptyList(),
    val graphicWatermark: PhotoGraphicWatermark? = null,
    val overlayPath: String? = null,
    val appliedMasks: List<List<MaskStroke>> = emptyList(),
    /** Opaque neural correction must survive later, separate eraser sessions. */
    val fullFrameCorrection: Boolean = false,
    val inpaintPreview: Bitmap? = null,
)

/** The destination-scoped draft retains the shared edits and undo stack across rotation.
 * No photo pixels or private URIs are persisted by this holder. */
internal class PhotoBatchDraftViewModel : ViewModel() {
    val edit = mutableStateOf(PhotoEditSnapshot())
    val undo = mutableStateListOf<PhotoEditSnapshot>()
    val redo = mutableStateListOf<PhotoEditSnapshot>()
    var index by mutableIntStateOf(0)
    var format by mutableStateOf(BatchPhotoFormat.JPEG)
    var removeGps by mutableStateOf(true)
    var removeExif by mutableStateOf(false)
}

private data class PhotoBatchWorkspace(
    val rows: List<DownloadEntity>, val state: BatchPhotoState,
    val busy: Boolean, val ready: Boolean, val draft: PhotoBatchDraftViewModel,
    val onStart: (List<DownloadEntity>, BatchPhotoSpec) -> Unit,
)

@Composable
internal fun SharedBatchPhotoWorkspace(rows: List<DownloadEntity>, state: BatchPhotoState,
    busy: Boolean, ready: Boolean, onBack: () -> Unit,
    onStart: (List<DownloadEntity>, BatchPhotoSpec) -> Unit, onCancel: () -> Unit) {
    val draft: PhotoBatchDraftViewModel = viewModel()
    val publicRows = rows.filter { !it.isPrivate && !it.hidden && it.savedUri != null }
    val row = publicRows.getOrNull(draft.index.coerceIn(0,(publicRows.size-1).coerceAtLeast(0)))
    MaterialTheme(colorScheme = com.domenota.medialoader.ui.theme.editorColorScheme()) {
        if (row == null) {
            Column(Modifier.fillMaxSize().background(Color.Black).padding(16.dp)) {
                TextButton(onBack) { Text("Назад") }
                Text("Выбранные фотографии недоступны.",color=Color.White)
                if (state.running) Button(onCancel) { Text("Отменить обработку") }
            }
        } else PhotoEditorContent(row, EditorExportState(), onBack, {}, onCancel,
            PhotoBatchWorkspace(publicRows,state,busy,ready && publicRows.size==rows.size,draft,onStart))
    }
}

@Composable
fun PhotoEditorScreen(
    item: DownloadEntity,
    exportState: EditorExportState,
    onBack: () -> Unit,
    onExport: (PhotoEditSpec) -> Unit,
    onCancelExport: () -> Unit,
) {
    MaterialTheme(colorScheme = com.domenota.medialoader.ui.theme.editorColorScheme()) {
        PhotoEditorContent(item, exportState, onBack, onExport, onCancelExport)
    }
}

@Composable
private fun PhotoEditorContent(
    item: DownloadEntity,
    exportState: EditorExportState,
    onBack: () -> Unit,
    onExport: (PhotoEditSpec) -> Unit,
    onCancelExport: () -> Unit,
    batch: PhotoBatchWorkspace? = null,
) {
    val context = LocalContext.current
    val uri = item.savedUri?.let(Uri::parse)
    val ownExport = batch?.state?.running ?: (exportState.exporting && exportState.sourceId == item.id)
    val busyWithOther = if (batch != null) batch.busy && !ownExport else
        exportState.exporting && exportState.sourceId != null && exportState.sourceId != item.id
    val locked = ownExport || busyWithOther || (batch != null && !batch.ready)
    val workspaceKey: Any = batch?.draft ?: item.id
    var originalInfo by remember(uri,item.sizeBytes,item.createdAtEpochMillis) { mutableStateOf<PhotoSourceInfo?>(null) }
    val localEdit = remember(item.id) { mutableStateOf(PhotoEditSnapshot()) }
    var edit by (batch?.draft?.edit ?: localEdit)
    val localUndo = remember(item.id) { mutableStateListOf<PhotoEditSnapshot>() }
    val localRedo = remember(item.id) { mutableStateListOf<PhotoEditSnapshot>() }
    val undoHistory = batch?.draft?.undo ?: localUndo
    val redoHistory = batch?.draft?.redo ?: localRedo
    val activeCrop = if (batch != null && originalInfo != null) PhotoBatchCrop.adapt(
        edit.crop,edit.cropPreset,edit.cropReferenceWidth,edit.cropReferenceHeight,
        requireNotNull(originalInfo).width,requireNotNull(originalInfo).height) else edit.crop
    var batchCompare by remember(workspaceKey) { mutableStateOf(false) }
    // A selected toolbar category is not an active crop gesture. The batch
    // overview must render the complete edit, including committed geometry.
    var batchCropEditing by remember(item.id) { mutableStateOf(false) }
    var batchExportDialog by rememberSaveable { mutableStateOf(false) }
    var batchReportDialog by rememberSaveable { mutableStateOf(false) }
    var batchExitDialog by remember { mutableStateOf(false) }

    // Editing other parameters must not re-anchor the canonical crop on a new photo.
    fun fromWorkspace(next: PhotoEditSnapshot): PhotoEditSnapshot {
        if (batch == null) return next
        if (next.crop == activeCrop && next.cropPreset == edit.cropPreset)
            return next.copy(crop=edit.crop,cropReferenceWidth=edit.cropReferenceWidth,
                cropReferenceHeight=edit.cropReferenceHeight)
        val source = originalInfo ?: return next
        return next.copy(cropReferenceWidth=source.width,cropReferenceHeight=source.height)
    }
    fun sharedBatchSpec(): BatchPhotoSpec {
        val draft = requireNotNull(batch).draft
        return BatchPhotoSpec(maxSide=0,format=draft.format,quality=95,
            removeGps=draft.removeGps,removeExif=draft.removeExif,
            editSpec=PhotoEditSpec(cropLeft=edit.crop.left,cropRight=edit.crop.right,
                cropTop=edit.crop.top,cropBottom=edit.crop.bottom,
                rotationDegrees=edit.rotation,mirrorHorizontal=edit.mirrorHorizontal,
                mirrorVertical=edit.mirrorVertical,geometry=edit.geometry,
                colorAdjustments=edit.color,textLayers=edit.textLayers,
                drawing=edit.drawing,localEffects=edit.localEffects,
                graphicWatermark=edit.graphicWatermark),
            cropPreset=edit.cropPreset,cropReferenceWidth=edit.cropReferenceWidth,
            cropReferenceHeight=edit.cropReferenceHeight)
    }
    var pendingGesture by remember(item.id) { mutableStateOf<PhotoEditSnapshot?>(null) }
    var textEditorOpen by remember(item.id) { mutableStateOf(false) }
    var drawingEditorOpen by remember(item.id) { mutableStateOf(false) }
    var localEffectEditorOpen by remember(item.id) { mutableStateOf(false) }
    var geometryEditorOpen by remember(item.id) { mutableStateOf(false) }
    var removeObjects by remember(item.id) { mutableStateOf(false) }
    var currentTab by rememberSaveable(workspaceKey) { mutableStateOf(PhotoWorkspaceTab.CROP) }
    var activeLevel by rememberSaveable(workspaceKey) { mutableStateOf(PhotoLevelControl.BRIGHTNESS) }
    var activeHslRange by rememberSaveable(workspaceKey) { mutableStateOf(HslRange.RED) }
    var activeHslControl by rememberSaveable(workspaceKey) { mutableStateOf(PhotoHslControl.HUE) }
    var cropTool by rememberSaveable(workspaceKey) { mutableStateOf("ratio") }
    var toolPanelOpen by rememberSaveable(workspaceKey) { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val cropWorkspace = currentTab == PhotoWorkspaceTab.CROP && (batch == null || batchCropEditing)
    val batchRenderKey = if(batch!=null) listOf(uri, originalInfo, edit, activeCrop, batch.draft.format,
        currentTab, cropWorkspace) else null
    var composedPreview by remember(item.id,batchRenderKey) { mutableStateOf<Bitmap?>(null) }
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
        hasUnsavedChanges = dirty && !ownExport, onExit = {
            if (batch != null && ownExport) batchExitDialog = true else onBack()
        }
    )
    LaunchedEffect(uri,item.sizeBytes,item.createdAtEpochMillis) {
        if (uri != null) {
            var pending:PhotoSourceInfo?=null
            try {
                withContext(Dispatchers.IO) { pending=readPhotoSource(context,uri,true) }
                originalInfo=pending
                pending=null
                if(originalInfo==null)previewError="Не удалось открыть изображение."
            } finally { pending?.preview?.recycle() }
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
    LaunchedEffect(info, edit.color, cropWorkspace) {
        // Crop and composed editing have separate preview consumers; never
        // render both full color pipelines on every slider movement.
        if (!cropWorkspace) {
            cropColorPreview = null
            return@LaunchedEffect
        }
        val source = info?.preview ?: return@LaunchedEffect
        delay(35L)
        cropColorPreview = withContext(Dispatchers.Default) {
            PhotoColorMath.apply(source, edit.color)
        }
    }
    LaunchedEffect(activeCrop, edit.rotation, edit.mirrorHorizontal,
        edit.mirrorVertical, edit.geometry) {
        photoViewport.reset()
    }

    // Only bake the graphic mark outside its editing tab. While dragging, its
    // overlay moves independently without decoding/recompositing the full bitmap.
    val bakedGraphic = if (currentTab == PhotoWorkspaceTab.WATERMARK)
        null else edit.graphicWatermark

    // Reuse one reduced bitmap while scrubbing RGB points. The full source is
    // retained for all other tools and final export.
    var rgbFastPreview by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(info?.preview) {
        val source = info?.preview ?: return@LaunchedEffect
        rgbFastPreview = withContext(Dispatchers.Default) {
            val edge = maxOf(source.width, source.height)
            if (edge <= 720) source
            else {
                val scale = 720f / edge
                Bitmap.createScaledBitmap(source,
                    (source.width * scale).toInt().coerceAtLeast(1),
                    (source.height * scale).toInt().coerceAtLeast(1), true)
            }
        }
    }
    DisposableEffect(rgbFastPreview) {
        val preview = rgbFastPreview
        onDispose {
            if (preview != null && preview !== info?.preview && !preview.isRecycled)
                preview.recycle()
        }
    }
    // All non-crop tabs show the *actual* edited geometry, color and text.
    // Debounced previews are calculated away from the UI thread; full-resolution
    // export applies the same operations in the same order.
    LaunchedEffect(info, activeCrop, edit.rotation, edit.mirrorHorizontal, edit.mirrorVertical,
        edit.geometry, edit.color, edit.textLayers, edit.drawing, edit.localEffects,
        bakedGraphic, currentTab, cropWorkspace, activeLevel, rgbFastPreview, batch?.draft?.format) {
        val source = info?.preview
        if (source == null || cropWorkspace) {
            composedPreview = null
            previewError = null
            return@LaunchedEffect
        }
        val rapidCurves = currentTab == PhotoWorkspaceTab.LEVELS &&
            activeLevel == PhotoLevelControl.RGB_CURVES
        if (!rapidCurves) delay(35L)
        try {
            var pending: Bitmap? = null
            val previewSpec = PhotoEditSpec(rotationDegrees=edit.rotation,
                mirrorHorizontal=edit.mirrorHorizontal,mirrorVertical=edit.mirrorVertical,
                geometry=edit.geometry,colorAdjustments=edit.color,textLayers=edit.textLayers,
                drawing=edit.drawing,localEffects=edit.localEffects,graphicWatermark=bakedGraphic)
            try {
                withContext(Dispatchers.Default) {
                    val input = if(rapidCurves) rgbFastPreview ?: source else source
                    val rendered=PhotoRenderPipeline.render(context,input,activeCrop,previewSpec,
                        fitInside=batch!=null,flatten=batch?.draft?.format==BatchPhotoFormat.JPEG)
                    pending=if(rendered===input) input.copy(Bitmap.Config.ARGB_8888,false) else rendered
                }
                composedPreview=pending
                pending=null
            } finally { pending?.recycle() }
            previewError = null
        } catch (error: CancellationException) {
            // Expected when a newer adjustment supersedes this preview.
            throw error
        } catch (error: Exception) {
            com.domenota.medialoader.core.logging.AppLog.w(
                "PhotoEditor", "Preview render failed: ${error.javaClass.simpleName}")
            previewError = photoPreviewFailureMessage(error)
        }
    }

    if (textEditorOpen && info != null) {
        EditorTextDialog(
            initial = edit.textLayers,
            loadPreview = { _: Long -> withContext(Dispatchers.Default) {
                val transformed = PhotoGeometryMath.preview(info.preview, activeCrop, edit.rotation,
                    edit.mirrorHorizontal, edit.mirrorVertical, edit.geometry)
                val colored = PhotoColorMath.apply(transformed, edit.color)
                if (colored !== transformed) transformed.recycle()
                colored
            } },
            onApply = { layers -> commit(edit.copy(textLayers = layers)); textEditorOpen = false },
            onDismiss = { textEditorOpen = false },
            fitInside = batch != null,
        )
    }

    // Build a clean drawing canvas rather than painting on the already baked
    // marks. Existing vectors stay selectable across reopening the editor.
    var drawingBackground by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(drawingEditorOpen, info, activeCrop, edit.rotation, edit.mirrorHorizontal,
        edit.mirrorVertical, edit.geometry, edit.color, edit.graphicWatermark) {
        if (!drawingEditorOpen) { drawingBackground = null; return@LaunchedEffect }
        val source = info?.preview ?: return@LaunchedEffect
        drawingBackground = withContext(Dispatchers.Default) {
            val transformed = PhotoGeometryMath.preview(source, activeCrop, edit.rotation,
                edit.mirrorHorizontal, edit.mirrorVertical, edit.geometry)
            val colored = PhotoColorMath.apply(transformed, edit.color)
            if (colored !== transformed) transformed.recycle()
            val graphic = edit.graphicWatermark
            if (graphic == null) colored else {
                val output = colored.copy(Bitmap.Config.ARGB_8888, true)
                try {
                    PhotoGraphicWatermarkRenderer.draw(context, android.graphics.Canvas(output),
                        output.width, output.height, graphic)
                    output
                } catch (error: Exception) {
                    output.recycle()
                    throw error
                } finally {
                    if (!colored.isRecycled) colored.recycle()
                }
            }
        }
    }
    DisposableEffect(drawingBackground) {
        val owned = drawingBackground
        onDispose { if (owned != null && !owned.isRecycled) owned.recycle() }
    }
    if (drawingEditorOpen && drawingBackground != null) {
        PhotoDrawDialog(
            preview = requireNotNull(drawingBackground),
            initial = edit.drawing,
            textLayers = edit.textLayers,
            fitInside = batch != null,
            onApply = { marks ->
                commit(edit.copy(drawing = marks))
                drawingEditorOpen = false
            },
            onDismiss = { drawingEditorOpen = false },
        )
    }

    // Local effect masks are edited on the clean, color-adjusted photograph.
    // Existing text and drawings remain vector overlays above the effect.
    var effectBackground by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(localEffectEditorOpen, info, activeCrop, edit.rotation,
        edit.mirrorHorizontal, edit.mirrorVertical, edit.geometry, edit.color) {
        if (!localEffectEditorOpen) { effectBackground = null; return@LaunchedEffect }
        val src = info?.preview ?: return@LaunchedEffect
        effectBackground = withContext(Dispatchers.Default) {
            val transformed = PhotoGeometryMath.preview(src, activeCrop, edit.rotation,
                edit.mirrorHorizontal, edit.mirrorVertical, edit.geometry)
            val colored = PhotoColorMath.apply(transformed, edit.color)
            if (colored !== transformed) transformed.recycle()
            colored
        }
    }
    DisposableEffect(effectBackground) {
        val owned = effectBackground
        onDispose { if (owned != null && !owned.isRecycled) owned.recycle() }
    }
    if (localEffectEditorOpen && effectBackground != null) {
        PhotoLocalEffectsDialog(
            source = requireNotNull(effectBackground),
            initial = edit.localEffects,
            onApply = { strokes ->
                commit(edit.copy(localEffects = strokes))
                localEffectEditorOpen = false
            },
            onDismiss = { localEffectEditorOpen = false },
        )
    }

    var geometryBackground by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(geometryEditorOpen, info, activeCrop, edit.rotation,
        edit.mirrorHorizontal, edit.mirrorVertical) {
        if (!geometryEditorOpen) { geometryBackground = null; return@LaunchedEffect }
        val original = info?.preview ?: return@LaunchedEffect
        geometryBackground = withContext(Dispatchers.Default) {
            TextLayerRenderer.preview(original, activeCrop, edit.rotation,
                edit.mirrorHorizontal, edit.mirrorVertical)
        }
    }
    DisposableEffect(geometryBackground) {
        val owned = geometryBackground
        onDispose { if (owned != null && !owned.isRecycled) owned.recycle() }
    }
    if (geometryEditorOpen && geometryBackground != null) {
        PhotoGeometryDialog(
            source = requireNotNull(geometryBackground),
            initial = edit.geometry,
            onApply = { next ->
                commit(edit.copy(geometry = next))
                batchCropEditing=false
                batchCompare=false
                geometryEditorOpen = false
            },
            onDismiss = { geometryEditorOpen = false },
        )
    }

    Scaffold(
        // Parent Scaffold already accounts for Android system bars.
        // Avoid reserving a second system-navigation inset in the editor.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.Black,
        contentColor = Color.White,
        topBar = {
            // The AI eraser has its own back/undo/redo/compare chrome.
            // Never render the main editor header a second time over it.
            if (!removeObjects) Row(Modifier.fillMaxWidth().background(Color.Black).padding(horizontal = 7.dp, vertical = 8.dp),
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
                if (batch != null) {
                    Text("Пакетный редактор", color = Color.White, fontSize = 13.sp)
                } else TextButton(
                    onClick = {
                        onExport(PhotoEditSpec(
                            cropLeft = edit.crop.left, cropRight = edit.crop.right,
                            cropTop = edit.crop.top, cropBottom = edit.crop.bottom,
                            rotationDegrees = edit.rotation,
                            geometry = edit.geometry,
                            mirrorHorizontal = edit.mirrorHorizontal, mirrorVertical = edit.mirrorVertical,
                            inpaintOverlayPath = edit.overlayPath, textLayers = edit.textLayers,
                            colorAdjustments = edit.color,
                            drawing = edit.drawing,
                            localEffects = edit.localEffects,
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
                earlierFullFrameCorrection = edit.fullFrameCorrection,
                earlierOverlayPath = edit.overlayPath,
                onAccept = { bitmap, masks, path, correctedFullFrame ->
                    if (!locked) {
                        commit(edit.copy(overlayPath = path, appliedMasks = masks,
                            fullFrameCorrection = correctedFullFrame, inpaintPreview = bitmap))
                        removeObjects = false
                    }
                },
                onClose = { removeObjects = false },
            )
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).background(Color.Black)) {
            if (batch != null) BatchPhotoFilmstrip(batch.rows,batch.draft.index,
                enabled = !locked, compare = batchCompare, onCompare = { batchCompare = !batchCompare },
                onSelect = { next -> finishGesture(); batchCropEditing=false; batch.draft.index = next })
            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp, vertical = 10.dp)
                .then(if(batch!=null) Modifier.testTag("batch-preview") else Modifier),
                contentAlignment = Alignment.Center) {
                if (info == null) {
                    Text(previewError ?: "Открываем изображение…", color = Color.LightGray)
                } else if (batch != null && batchCompare) {
                    Image(info.preview.asImageBitmap(),"Оригинал",Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
                } else if (!cropWorkspace && composedPreview != null) {
                    Box(Modifier.fillMaxSize().then(if(batch!=null)Modifier.testTag("batch-edited-preview") else Modifier)){
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
                    }
                } else if(batch!=null&&!cropWorkspace){
                    Text(previewError ?: "Обновляем предпросмотр…",color=Color.LightGray)
                } else {
                    PhotoWorkspacePreview(
                        info = info, edit = edit.copy(crop=activeCrop), colorPreview = cropColorPreview,
                        cropFrameVisible = cropWorkspace && !locked,
                        onCropChange = {
                            if (pendingGesture == null) pendingGesture = edit
                            edit = fromWorkspace(edit.copy(crop = it))
                        },
                        onCropFinished = ::finishGesture,
                    )
                }
            }
            if (ownExport && batch == null) {
                // One shared ML loading symbol instead of an unrelated purple
                // export line and a second "Экспорт фото" caption.
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    AiPrismWorkingIndicator(
                        label = "",
                        showLabel = false,
                        tileSize = 62.dp,
                        modifier = Modifier.semantics {
                            progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(
                                exportState.progress / 100f, 0f..1f)
                            contentDescription = "Сохранение фотографии"
                        },
                    )
                    Spacer(Modifier.width(18.dp))
                    IconButton(onClick = onCancelExport) {
                        Icon(Icons.Rounded.Close, "Отменить экспорт фото",
                            tint = Color.White)
                    }
                }
            } else if (busyWithOther) {
                Text("Обрабатывается другой файл. Редактирование временно недоступно.",
                    modifier = Modifier.padding(12.dp), color = Color.LightGray)
            }
            if (!removeObjects) {
                PhotoWorkspaceTools(
                    tab = currentTab, onTab = { next ->
                        if (currentTab == next) toolPanelOpen = !toolPanelOpen
                        else { currentTab = next; toolPanelOpen = false }
                        if(batch!=null){batchCropEditing=next==PhotoWorkspaceTab.CROP&&toolPanelOpen;batchCompare=false}
                    },
                    panelOpen = toolPanelOpen, onPanelOpen = {
                        toolPanelOpen = it
                        if(batch!=null&&currentTab==PhotoWorkspaceTab.CROP)batchCropEditing=it
                    },
                    cropTool = cropTool, onCropTool = {
                        cropTool = it; toolPanelOpen = (it == "ratio")
                        if(batch!=null){batchCropEditing=it=="ratio";batchCompare=false}
                    },
                    edit = edit.copy(crop=activeCrop), info = info, locked = locked,
                    allowAi = batch == null,
                    activeLevel = activeLevel, onActiveLevel = {
                        activeLevel = it; toolPanelOpen = true
                    },
                    activeHslRange = activeHslRange, onHslRange = {
                        activeHslRange = it; toolPanelOpen = true
                    },
                    activeHslControl = activeHslControl, onHslControl = {
                        activeHslControl = it; toolPanelOpen = true
                    },
                    onEdit = { next -> commit(fromWorkspace(next)); batchCompare = false },
                    onLiveEdit = { update ->
                        if (pendingGesture == null) pendingGesture = edit
                        edit = fromWorkspace(update)
                        batchCompare = false
                    },
                    onEditFinished = ::finishGesture,
                    onText = { textEditorOpen = true },
                    onDraw = { drawingEditorOpen = true },
                    onMosaic = { localEffectEditorOpen = true },
                    onGeometry = { geometryEditorOpen = true },
                    onPickGraphic = { graphicPicker.launch(arrayOf("image/png", "image/jpeg", "image/webp")) },
                    onLaMa = { if (batch == null) removeObjects = true },
                )
            }
            if (batch != null) {
                if (!batch.ready) Text("Не все выбранные фотографии доступны.",color=Color.LightGray,
                    modifier=Modifier.padding(horizontal=12.dp))
                if (batch.state.total > 0) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("Сохранено: ${batch.state.outcomes.count { it.error == null }} / ${batch.state.total}",
                            color=Color.White,modifier=Modifier.weight(1f))
                        TextButton({batchReportDialog=true}) { Text("Отчёт") }
                    }
                }
                if (ownExport) {
                    LinearProgressIndicator(progress={batch.state.outcomes.size.toFloat()/batch.state.total.coerceAtLeast(1)},
                        modifier=Modifier.fillMaxWidth())
                    Button(onCancelExport,Modifier.fillMaxWidth().padding(horizontal=10.dp)) { Text("Отменить обработку") }
                } else Button({ batchExportDialog=true },Modifier.fillMaxWidth().padding(horizontal=10.dp).testTag("batch-export"),
                    enabled=!locked && info!=null) { Text("Обработать ${batch.rows.size} фото") }
            }
            previewError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp)) }
            exportState.message
                ?.takeIf { exportState.sourceId == null || exportState.sourceId == item.id }
                ?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) }
        }
    }
    if (batch != null && batchExportDialog) {
        val draft = batch.draft
        AlertDialog(onDismissRequest={batchExportDialog=false}, title={Text("Экспорт фотографий")},
            text={
                Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("Исходное разрешение с учётом кадрирования и поворота. Оригиналы сохраняются.")
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        BatchPhotoFormat.entries.forEach { format ->
                            FilterChip(selected=draft.format==format,onClick={draft.format=format},label={Text(format.name)})
                        }
                    }
                    if(draft.format==BatchPhotoFormat.JPEG) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(draft.removeGps,{draft.removeGps=it},enabled=!draft.removeExif)
                            Text("Удалить GPS")
                        }
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(draft.removeExif,{draft.removeExif=it})
                            Text("Удалить EXIF")
                        }
                        Text("Сохраняются основные данные камеры и даты; ориентация исправляется.")
                    } else Text("PNG и WebP сохраняются без исходных метаданных.")
                }
            },confirmButton={TextButton({
                batchExportDialog=false
                if(!locked) batch.onStart(batch.rows,sharedBatchSpec())
            },enabled=!locked,modifier=Modifier.testTag("batch-confirm-export")) { Text("Обработать ${batch.rows.size} фото") }},
            dismissButton={TextButton({batchExportDialog=false}){Text("Отмена")}})
    }
    if(batch != null && batchReportDialog) {
        AlertDialog(onDismissRequest={batchReportDialog=false},title={Text("Результаты обработки")},
            text={Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Сохранено: ${batch.state.outcomes.count { it.error == null }} / ${batch.state.total}")
                if(batch.state.cancelled) Text("Обработка отменена. Готовые копии сохранены.")
                batch.state.failed.forEach { Text("${it.name}: ${it.error}",color=MaterialTheme.colorScheme.error) }
            }},confirmButton={TextButton({batchReportDialog=false}){Text("Готово")}},
            dismissButton={if(!locked && batch.state.failed.isNotEmpty()) TextButton({
                batchReportDialog=false
                batch.onStart(batch.rows.filter { row->batch.state.failed.any { it.id==row.id } },
                    batch.state.spec ?: sharedBatchSpec())
            }) { Text("Повторить ошибки") }})
    }
    if(batchExitDialog) AlertDialog(onDismissRequest={batchExitDialog=false},title={Text("Обработка продолжается")},
        text={Text("Можно вернуться в галерею. Обработка продолжится в фоне.")},
        confirmButton={TextButton({batchExitDialog=false;onBack()}){Text("В галерею")}},
        dismissButton={TextButton({batchExitDialog=false}){Text("Остаться")}})
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
                precisePhotoHandles = true,
                onCompareOriginalChange = { showOriginal = it },
                onCropChangeFinished = onCropFinished,
            )
        }
    }
}

@Composable
private fun PhotoWorkspaceTools(
    tab: PhotoWorkspaceTab, onTab: (PhotoWorkspaceTab) -> Unit,
    panelOpen: Boolean, onPanelOpen: (Boolean) -> Unit,
    cropTool: String, onCropTool: (String) -> Unit,
    edit: PhotoEditSnapshot, info: PhotoSourceInfo?, locked: Boolean,
    allowAi: Boolean,
    activeLevel: PhotoLevelControl, onActiveLevel: (PhotoLevelControl) -> Unit,
    activeHslRange: HslRange, onHslRange: (HslRange) -> Unit,
    activeHslControl: PhotoHslControl, onHslControl: (PhotoHslControl) -> Unit,
    onEdit: (PhotoEditSnapshot) -> Unit,
    onLiveEdit: (PhotoEditSnapshot) -> Unit,
    onEditFinished: () -> Unit,
    onText: () -> Unit, onDraw: () -> Unit, onMosaic: () -> Unit,
    onGeometry: () -> Unit,
    onPickGraphic: () -> Unit, onLaMa: () -> Unit,
) {
    var effectCategory by remember { mutableStateOf(PhotoEffectCategory.ALL) }
    var levelGroup by remember { mutableStateOf(PhotoLevelGroup.TONE) }
    val favoritesContext = LocalContext.current
    val favoriteStorage = remember(favoritesContext) {
        favoritesContext.getSharedPreferences("ml_filter_favorites", Context.MODE_PRIVATE)
    }
    var favoriteEffects by remember(favoriteStorage) {
        mutableStateOf(PhotoEffect.entries.filter { it.name in
            favoriteStorage.getStringSet("selected", emptySet<String>()).orEmpty() }.toSet())
    }
    // Stage 1: a persistent, compact tool strip and a contextual panel that
    // appears only when the user selects a parameter. No empty 188/242dp tray.
    val hasParameters = when (tab) {
        PhotoWorkspaceTab.CROP -> cropTool == "ratio"
        PhotoWorkspaceTab.LEVELS, PhotoWorkspaceTab.EFFECTS,
        PhotoWorkspaceTab.WATERMARK -> true
        else -> false
    }
    Column(Modifier.fillMaxWidth().background(Color(0xFF101012)).animateContentSize()) {
        if (panelOpen && hasParameters) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(18.dp)).background(Color(0xFF252529))) {
                Row(Modifier.fillMaxWidth().height(34.dp).padding(start = 15.dp, end = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(when (tab) {
                        PhotoWorkspaceTab.CROP -> "Соотношение сторон"
                        PhotoWorkspaceTab.LEVELS -> activeLevel.title
                        PhotoWorkspaceTab.EFFECTS -> "Интенсивность · " + edit.color.effect.label
                        PhotoWorkspaceTab.WATERMARK -> "Параметры водяного знака"
                        else -> ""
                    }, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    if (tab == PhotoWorkspaceTab.LEVELS &&
                        activeLevel in setOf(PhotoLevelControl.POSTERIZATION,
                            PhotoLevelControl.GRAIN, PhotoLevelControl.DENOISE,
                            PhotoLevelControl.NOISE)) {
                        IconButton(onClick = {
                            onEdit(edit.copy(color = resetPhotoCreativeControl(edit.color, activeLevel)))
                        }, modifier = Modifier.size(32.dp),
                            enabled = !locked && info != null) {
                            Icon(Icons.Rounded.RestartAlt, "Сбросить параметр",
                                tint = Color.LightGray, modifier = Modifier.size(20.dp))
                        }
                    }
                    IconButton(onClick = { onPanelOpen(false) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.KeyboardArrowDown, "Свернуть параметры",
                            tint = Color.LightGray, modifier = Modifier.size(22.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().height(when (tab) {
                    PhotoWorkspaceTab.LEVELS -> when (activeLevel) {
                        PhotoLevelControl.RGB_CURVES -> 282.dp
                        PhotoLevelControl.HSL -> 168.dp
                        PhotoLevelControl.NOISE -> 232.dp
                        PhotoLevelControl.GLOW, PhotoLevelControl.VIGNETTE,
                        PhotoLevelControl.DISPERSION -> 112.dp
                        else -> 64.dp
                    }
                    PhotoWorkspaceTab.WATERMARK -> 82.dp
                    PhotoWorkspaceTab.EFFECTS -> 56.dp
                    else -> 64.dp
                }), contentAlignment = Alignment.Center) {
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
                    if (activeLevel == PhotoLevelControl.RGB_CURVES) {
                        PhotoCurvesPanel(
                            curves = edit.color.rgbCurves,
                            enabled = !locked && info != null,
                            onLiveEdit = { curves ->
                                onLiveEdit(edit.copy(color = edit.color.copy(rgbCurves = curves)))
                            },
                            onEditFinished = onEditFinished,
                            onCommit = { curves ->
                                onEdit(edit.copy(color = edit.color.copy(rgbCurves = curves)))
                            },
                        )
                    } else if (activeLevel == PhotoLevelControl.HSL) {
                    val correction = edit.color.hsl[activeHslRange] ?: HslCorrection()
                    val value = when (activeHslControl) {
                        PhotoHslControl.HUE -> correction.hue
                        PhotoHslControl.SATURATION -> correction.saturation
                        PhotoHslControl.LIGHTNESS -> correction.lightness
                    }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            HslRange.entries.forEach { range ->
                                FilterChip(selected = activeHslRange == range,
                                    enabled = !locked && info != null,
                                    onClick = { onHslRange(range) },
                                    leadingIcon = {
                                        Box(Modifier.size(14.dp).background(
                                            photoHslChannelColor(range), CircleShape))
                                    },
                                    label = { Text(range.label, fontSize = 11.sp) })
                            }
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            PhotoHslControl.entries.forEach { control ->
                                FilterChip(selected = activeHslControl == control,
                                    enabled = !locked && info != null,
                                    onClick = { onHslControl(control) },
                                    label = { Text(control.title, fontSize = 11.sp) })
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(value.roundToInt().toString(), fontSize = 11.sp,
                                color = Color.LightGray, modifier = Modifier.width(34.dp))
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
                    } else if (activeLevel == PhotoLevelControl.NOISE) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            PhotoNoiseParameterRow("Степень", edit.color.noiseDegree,
                                enabled = !locked && info != null,
                                onChange = { onLiveEdit(edit.copy(color =
                                    edit.color.copy(noiseDegree = it))) }, onEditFinished)
                            PhotoNoiseParameterRow("Светлые участки", edit.color.noiseHighlights,
                                enabled = !locked && info != null,
                                onChange = { onLiveEdit(edit.copy(color =
                                    edit.color.copy(noiseHighlights = it))) }, onEditFinished)
                            PhotoNoiseParameterRow("Размер", edit.color.noiseSize,
                                enabled = !locked && info != null,
                                onChange = { onLiveEdit(edit.copy(color =
                                    edit.color.copy(noiseSize = it))) }, onEditFinished)
                            PhotoNoiseParameterRow("Грубость", edit.color.noiseRoughness,
                                enabled = !locked && info != null,
                                onChange = { onLiveEdit(edit.copy(color =
                                    edit.color.copy(noiseRoughness = it))) }, onEditFinished)
                        }
                    } else if (activeLevel in setOf(
                        PhotoLevelControl.GLOW, PhotoLevelControl.VIGNETTE,
                        PhotoLevelControl.DISPERSION)) {
                        val strength = when (activeLevel) {
                            PhotoLevelControl.GLOW -> edit.color.glow
                            PhotoLevelControl.VIGNETTE -> edit.color.vignette
                            else -> edit.color.dispersion
                        }
                        val size = when (activeLevel) {
                            PhotoLevelControl.GLOW -> edit.color.glowSize
                            PhotoLevelControl.VIGNETTE -> edit.color.vignetteSize
                            else -> edit.color.dispersionSize
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 17.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Сила", fontSize = 12.sp, color = Color.LightGray)
                                Slider(strength, onValueChange = { next ->
                                    val color = when (activeLevel) {
                                        PhotoLevelControl.GLOW -> edit.color.copy(glow = next)
                                        PhotoLevelControl.VIGNETTE -> edit.color.copy(vignette = next)
                                        else -> edit.color.copy(dispersion = next)
                                    }
                                    onLiveEdit(edit.copy(color = color))
                                }, modifier = Modifier.weight(1f).padding(horizontal = 7.dp),
                                    enabled = !locked && info != null, valueRange = 0f..100f,
                                    onValueChangeFinished = onEditFinished)
                                Text(strength.roundToInt().toString(), color = Color.White, fontSize = 12.sp)
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Размер", fontSize = 11.sp, color = Color.LightGray)
                                Slider(size, onValueChange = { next ->
                                    val color = when (activeLevel) {
                                        PhotoLevelControl.GLOW -> edit.color.copy(glowSize = next)
                                        PhotoLevelControl.VIGNETTE -> edit.color.copy(vignetteSize = next)
                                        else -> edit.color.copy(dispersionSize = next)
                                    }
                                    onLiveEdit(edit.copy(color = color))
                                }, modifier = Modifier.weight(1f).padding(horizontal = 7.dp),
                                    enabled = !locked && info != null, valueRange = 0f..100f,
                                    onValueChangeFinished = onEditFinished)
                                Text(size.roundToInt().toString(), color = Color.White, fontSize = 11.sp)
                            }
                        }
                    } else {
                    val value = when(activeLevel) {
                        PhotoLevelControl.EXPOSURE -> edit.color.exposure
                        PhotoLevelControl.BRIGHTNESS -> edit.color.brightness
                        PhotoLevelControl.CONTRAST -> edit.color.contrast
                        PhotoLevelControl.SATURATION -> edit.color.saturation
                        PhotoLevelControl.VIBRANCE -> edit.color.vibrance
                        PhotoLevelControl.WARMTH -> edit.color.warmth
                        PhotoLevelControl.TINT -> edit.color.tint
                        PhotoLevelControl.SHADOWS -> edit.color.shadows
                        PhotoLevelControl.HIGHLIGHTS -> edit.color.highlights
                        PhotoLevelControl.BLACK_POINT -> edit.color.blackPoint
                        PhotoLevelControl.CURVE -> edit.color.curve
                        PhotoLevelControl.SHARPNESS -> edit.color.sharpness
                        PhotoLevelControl.CLARITY -> edit.color.clarity
                        PhotoLevelControl.POSTERIZATION -> edit.color.posterization
                        PhotoLevelControl.GRAIN -> edit.color.grain
                        PhotoLevelControl.DENOISE -> edit.color.denoise
                        else -> 0f // Curves, HSL and fine-tuning have their own panels
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
                                    PhotoLevelControl.VIBRANCE -> edit.color.copy(vibrance = next)
                                    PhotoLevelControl.WARMTH -> edit.color.copy(warmth = next)
                                    PhotoLevelControl.TINT -> edit.color.copy(tint = next)
                                    PhotoLevelControl.SHADOWS -> edit.color.copy(shadows = next)
                                    PhotoLevelControl.HIGHLIGHTS -> edit.color.copy(highlights = next)
                                    PhotoLevelControl.BLACK_POINT -> edit.color.copy(blackPoint = next)
                                    PhotoLevelControl.CURVE -> edit.color.copy(curve = next)
                                    PhotoLevelControl.SHARPNESS -> edit.color.copy(sharpness = next)
                                    PhotoLevelControl.CLARITY -> edit.color.copy(clarity = next)
                                    PhotoLevelControl.POSTERIZATION -> edit.color.copy(posterization = next)
                                    PhotoLevelControl.GRAIN -> edit.color.copy(grain = next)
                                    PhotoLevelControl.DENOISE -> edit.color.copy(denoise = next)
                                    else -> edit.color
                                }
                                onLiveEdit(edit.copy(color = color))
                            },
                            onValueChangeFinished = onEditFinished,
                            valueRange = if (activeLevel in setOf(
                                PhotoLevelControl.POSTERIZATION, PhotoLevelControl.GRAIN,
                                PhotoLevelControl.DENOISE)) 0f..100f else -100f..100f,
                            enabled = !locked && info != null,
                        )
                    }
                    }
                }
                PhotoWorkspaceTab.EFFECTS -> {
                    val strength = edit.color.effectStrength
                    Row(Modifier.fillMaxWidth().padding(horizontal = 17.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Сила", fontSize = 12.sp, color = Color.LightGray)
                        Slider(strength, onValueChange = { next ->
                            onLiveEdit(edit.copy(color = edit.color.copy(effectStrength = next)))
                        }, modifier = Modifier.weight(1f).padding(horizontal = 7.dp),
                            enabled = !locked && info != null, valueRange = 0f..100f,
                            onValueChangeFinished = onEditFinished)
                        Text(strength.roundToInt().toString(), color = Color.White, fontSize = 12.sp)
                    }
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
            }
        }
        if (tab == PhotoWorkspaceTab.EFFECTS) {
            Row(Modifier.fillMaxWidth().height(43.dp).horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PhotoEffectCategory.entries.filter { it != PhotoEffectCategory.POPULAR }.forEach { category ->
                    FilterChip(selected = effectCategory == category,
                        onClick = { effectCategory = category },
                        enabled = !locked,
                        label = { Text(category.label, fontSize = 11.sp) })
                }
            }
        }
        if (tab == PhotoWorkspaceTab.LEVELS) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                PhotoLevelGroup.entries.forEach { group ->
                    FilterChip(selected = levelGroup == group,
                        onClick = { levelGroup = group; onPanelOpen(false) },
                        label = { Text(group.title, fontSize = 12.sp) },
                        enabled = !locked)
                }
            }
        }
        if (tab == PhotoWorkspaceTab.EFFECTS) {
            // Only compose thumbnails currently on screen: dozens of eager bitmap
            // filters on the UI thread previously stalled the entire editor.
            val available = PhotoEffectCatalog.forCategory(effectCategory, favoriteEffects)
            LazyRow(Modifier.fillMaxWidth().height(68.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically) {
                items(available, key = { it.name }) { effect ->
                    PhotoEffectCard(effect = effect, info = info,
                        selected = edit.color.effect == effect,
                        favorite = effect in favoriteEffects,
                        enabled = !locked && info != null,
                        onFavorite = {
                            favoriteEffects = if (effect in favoriteEffects)
                                favoriteEffects - effect else favoriteEffects + effect
                            favoriteStorage.edit().putStringSet("selected",
                                favoriteEffects.map { it.name }.toSet()).apply()
                        },
                        onClick = {
                            onEdit(edit.copy(color = edit.color.copy(effect = effect)))
                            onPanelOpen(true)
                        })
                }
            }
        } else Row(Modifier.fillMaxWidth().height(68.dp).horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            when(tab) {
                PhotoWorkspaceTab.CROP -> {
                    PhotoToolCard("Геометрия", Icons.Rounded.AspectRatio, false,
                        onGeometry, !locked && info != null)
                    PhotoToolCard("Соотношение", Icons.Rounded.Crop, cropTool == "ratio",
                        { onCropTool("ratio") }, !locked)
                    PhotoToolCard("Поворот", Icons.Rounded.RotateRight, false,
                        { onCropTool("rotate"); onEdit(edit.copy(rotation = (edit.rotation + 90) % 360)) }, !locked)
                    PhotoToolCard("Горизонтально", Icons.Rounded.Flip, false,
                        { onCropTool("horizontal"); onEdit(edit.copy(mirrorHorizontal = !edit.mirrorHorizontal)) }, !locked)
                    PhotoToolCard("Вертикально", Icons.Rounded.FlipToFront, false,
                        { onCropTool("vertical"); onEdit(edit.copy(mirrorVertical = !edit.mirrorVertical)) }, !locked)
                }
                PhotoWorkspaceTab.LEVELS -> PhotoLevelControl.entries
                    .filter { photoLevelGroup(it) == levelGroup }.forEach { control ->
                    PhotoToolCard(control.title, photoLevelIcon(control),
                        activeLevel == control && panelOpen,
                        { onActiveLevel(control) }, !locked && info != null)
                }
                PhotoWorkspaceTab.EFFECTS -> Unit
                PhotoWorkspaceTab.TEXT -> {
                    PhotoToolCard("Надпись", Icons.Rounded.TextFields,
                        false, onText, !locked && info != null)
                    PhotoToolCard("Кисть и фигуры", Icons.Rounded.Brush,
                        false, onDraw, !locked && info != null)
                    PhotoToolCard("Мозаика", Icons.Rounded.GridOn,
                        false, onMosaic, !locked && info != null)
                }
                PhotoWorkspaceTab.WATERMARK -> {
                    PhotoToolCard("Графический знак", Icons.Rounded.AddPhotoAlternate,
                        false, onPickGraphic, !locked && info != null)
                    PhotoToolCard("Текстовый знак", Icons.Rounded.TextFields,
                        false, onText, !locked && info != null)
                    edit.graphicWatermark?.let { mark ->
                        PhotoToolCard("Параметры", Icons.Rounded.Tune, panelOpen,
                            { onPanelOpen(!panelOpen) }, !locked)
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
                    PhotoToolCard("AI-ластик", Icons.Rounded.AutoFixHigh,
                        false, onLaMa, !locked && info != null)
            }
        }
        Row(Modifier.fillMaxWidth().height(50.dp).horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            PhotoWorkspaceTab.entries.filter { allowAi || it != PhotoWorkspaceTab.AI }.forEach { category ->
                PhotoCategoryTab(
                    title = category.title,
                    icon = when (category) {
                        PhotoWorkspaceTab.CROP -> Icons.Rounded.Crop
                        PhotoWorkspaceTab.LEVELS -> Icons.Rounded.Tune

                        PhotoWorkspaceTab.EFFECTS -> Icons.Rounded.AutoAwesome
                        PhotoWorkspaceTab.TEXT -> Icons.Rounded.TextFields
                        PhotoWorkspaceTab.WATERMARK -> Icons.Rounded.BrandingWatermark
                        PhotoWorkspaceTab.AI -> Icons.Rounded.AutoFixHigh
                    },
                    selected = tab == category, onClick = { onTab(category) })
            }
        }
    }
}

@Composable
private fun PhotoNoiseParameterRow(
    title: String, value: Float, enabled: Boolean,
    onChange: (Float) -> Unit, onEditFinished: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(51.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.LightGray, fontSize = 11.sp,
            modifier = Modifier.width(105.dp), maxLines = 1)
        Slider(value = value, onValueChange = onChange, valueRange = 0f..100f,
            enabled = enabled, onValueChangeFinished = onEditFinished,
            modifier = Modifier.weight(1f))
        Text(value.roundToInt().toString(), color = Color.White,
            fontSize = 11.sp, modifier = Modifier.width(30.dp))
    }
}

private val PhotoEditorGold = com.domenota.medialoader.ui.components.editorSliderYellow

@Composable
internal fun PhotoCategoryTab(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick,
        shape = RoundedCornerShape(19.dp),
        color = if (selected) PhotoEditorGold else Color.Transparent) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp),
                tint = if (selected) Color.Black else Color.LightGray)
            Text(title, maxLines = 1, fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Color.Black else Color.LightGray)
        }
    }
}

@Composable
private fun PhotoEffectCard(
    effect: PhotoEffect, info: PhotoSourceInfo?,
    selected: Boolean, favorite: Boolean, enabled: Boolean,
    onFavorite: () -> Unit, onClick: () -> Unit,
) {
    EditorFilterCard(effect, info?.preview, selected, enabled, favorite, onFavorite, onClick)
}

/** Shared photo/batch/video filter card. Video keeps the existing read-only favorites category. */
@Composable
internal fun EditorFilterCard(
    effect: PhotoEffect, source: Bitmap?, selected: Boolean, enabled: Boolean,
    favorite: Boolean = false, onFavorite: (() -> Unit)? = null, onClick: () -> Unit,
) {
    // Bounded, aspect-preserving samples; expensive preset rendering is off-main.
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val edge = (94 * density).roundToInt().coerceIn(256, 384)
    var thumbnail by remember(source, effect, edge) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(source, effect, edge) {
        if (source == null) return@LaunchedEffect
        var pending: Bitmap? = null
        try {
            withContext(Dispatchers.Default) {
                pending = PhotoFilterThumbnail.render(source, effect, edge)
            }
            thumbnail = pending
            pending = null
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { thumbnail = null }
        finally { pending?.recycle() }
    }
    DisposableEffect(thumbnail) {
        val owned = thumbnail
        onDispose { if (owned != null && owned !== source && !owned.isRecycled)
            owned.recycle() }
    }
    Surface(modifier = Modifier.width(94.dp).height(67.dp).testTag("editor-filter-${effect.name}"),
        onClick = onClick, enabled = enabled,
        shape = RoundedCornerShape(13.dp),
        color = if (selected) PhotoEditorGold else Color(0xFF232328)) {
        Box(Modifier.fillMaxSize().padding(3.dp).clip(RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.BottomCenter) {
            thumbnail?.let {
                Image(it.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            Box(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .67f)),
                contentAlignment = Alignment.Center) {
                Text(effect.label, fontSize = 10.sp, color = Color.White, maxLines = 1)
            }
            if (effect != PhotoEffect.NONE && onFavorite != null) {
                IconButton(onClick = onFavorite, enabled = enabled,
                    modifier = Modifier.align(Alignment.TopEnd).size(30.dp)) {
                    Icon(if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = if (favorite) "Убрать из избранного" else "В избранное",
                        modifier = Modifier.size(18.dp),
                        tint = if (favorite) PhotoEditorGold else Color.White)
                }
            }
        }
    }
}

@Composable
internal fun PhotoToolCard(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean, onClick: () -> Unit, enabled: Boolean) {
    Surface(onClick = onClick, enabled = enabled,
        modifier = Modifier.width(94.dp).height(61.dp),
        shape = RoundedCornerShape(17.dp),
        color = if (selected) PhotoEditorGold else Color(0xFF252529)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(26.dp),
                tint = if (selected) Color.Black else Color.White)
            Spacer(Modifier.height(3.dp))
            Text(label, fontSize = 11.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, lineHeight = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) Color.Black else Color.White)
        }
    }
}

private fun readPhotoSource(context: android.content.Context, uri: Uri, orient: Boolean = false): PhotoSourceInfo? =
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 1280) sample *= 2
        val preview = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } ?: return@runCatching null
        val orientation = if (orient) context.contentResolver.openInputStream(uri)?.use {
            runCatching { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1) }
                .getOrDefault(1)
        } ?: 1 else 1
        val oriented = PhotoSourceOrientation.apply(preview,orientation)
        if (oriented !== preview) preview.recycle()
        val (width,height) = PhotoSourceOrientation.dimensions(bounds.outWidth,bounds.outHeight,orientation)
        PhotoSourceInfo(width,height,oriented)
    }.getOrNull()

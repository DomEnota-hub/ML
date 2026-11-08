package com.domenota.medialoader.ui.screens
import com.domenota.medialoader.ui.components.EditorSlider as Slider

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.ui.res.vectorResource
import com.domenota.medialoader.R
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush as GradientBrush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.domenota.medialoader.data.editor.LaMaInpaintingEngine
import com.domenota.medialoader.data.editor.LaMaModelStore
import com.domenota.medialoader.data.editor.MobileSamModelStore
import com.domenota.medialoader.data.editor.MobileSamSegmenter
import com.domenota.medialoader.data.editor.MaskPoint
import com.domenota.medialoader.data.editor.MaskStroke
import com.domenota.medialoader.data.editor.MaskActionGrouping
import com.domenota.medialoader.data.editor.DetectedGlyph
import com.domenota.medialoader.data.editor.OnDeviceTextGlyphDetector
import com.domenota.medialoader.data.editor.TextGlyphMask
import com.domenota.medialoader.data.editor.UnifiedMaskOverlay
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class RemovalStep(val bitmap: Bitmap, val maskBatches: List<List<MaskStroke>>)

@Composable
private fun RemovalToolButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp).background(
            if (selected) Color(0xFFFFC21C) else Color(0xFF292929),
            RoundedCornerShape(10.dp)),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(19.dp),
            tint = if (selected) Color(0xFF131313) else Color.White)
    }
}

/**
 * Fixed-size outlined action: its caller centers it relative to the whole screen,
 * never to the leftover width beside object mode controls.
 */
@Composable
private fun EraserStartButton(
    enabled: Boolean,
    onClick: () -> Unit,
    label: String = "Начать",
    width: Dp = 104.dp,
) {
    val shape = RoundedCornerShape(11.dp)
    val gradient = if (enabled) {
        listOf(Color(0xFFFFE075), Color(0xFFFFC21C), Color(0xFFE18C13))
    } else {
        listOf(Color(0xFF665B43), Color(0xFF665B43))
    }
    Box(
        Modifier.width(width).height(37.dp)
            .border(BorderStroke(1.dp, GradientBrush.horizontalGradient(gradient)), shape)
            .background(Color(0xFF252525), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Color.White else Color(0xFF999999),
            fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SelectionMagnifier(
    bitmap: ImageBitmap,
    overlay: ImageBitmap?,
    point: MaskPoint,
    viewport: IntSize,
    zoom: Float,
    brush: Float,
    eraser: Boolean,
    modifier: Modifier = Modifier,
) {
    if (viewport.width <= 0 || viewport.height <= 0) return
    val lensWidth = with(LocalDensity.current) {
        minOf(152.dp.toPx(), viewport.width * 0.43f).coerceAtLeast(1f).toDp()
    }
    val lensHeight = lensWidth * 0.72f
    Canvas(modifier.width(lensWidth).height(lensHeight)) {
        // Sample the source pixels, including the current mask, around the finger.
        val cropWidth = (bitmap.width * size.width / viewport.width / zoom / 2.5f)
            .roundToInt().coerceIn(1, bitmap.width)
        val cropHeight = (bitmap.height * size.height / viewport.height / zoom / 2.5f)
            .roundToInt().coerceIn(1, bitmap.height)
        val sourceX = (point.x * bitmap.width - cropWidth / 2f).roundToInt()
            .coerceIn(0, bitmap.width - cropWidth)
        val sourceY = (point.y * bitmap.height - cropHeight / 2f).roundToInt()
            .coerceIn(0, bitmap.height - cropHeight)
        val corner = CornerRadius(12.dp.toPx())
        val window = Path().apply {
            addRoundRect(RoundRect(Rect(0f, 0f, size.width, size.height), corner))
        }
        val outputSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
        clipPath(window) {
            drawImage(bitmap, IntOffset(sourceX, sourceY), IntSize(cropWidth, cropHeight),
                IntOffset.Zero, outputSize, filterQuality = FilterQuality.High)
            overlay?.let { mask ->
                val maskX = (sourceX.toFloat() / bitmap.width * mask.width).roundToInt()
                    .coerceIn(0, mask.width - 1)
                val maskY = (sourceY.toFloat() / bitmap.height * mask.height).roundToInt()
                    .coerceIn(0, mask.height - 1)
                val maskWidth = (cropWidth.toFloat() / bitmap.width * mask.width)
                    .roundToInt().coerceAtLeast(1).coerceAtMost(mask.width - maskX)
                val maskHeight = (cropHeight.toFloat() / bitmap.height * mask.height)
                    .roundToInt().coerceAtLeast(1).coerceAtMost(mask.height - maskY)
                drawImage(mask, IntOffset(maskX, maskY), IntSize(maskWidth, maskHeight),
                    IntOffset.Zero, outputSize, filterQuality = FilterQuality.High)
            }
            val guideX = (point.x * bitmap.width - sourceX) / cropWidth * size.width
            val guideY = (point.y * bitmap.height - sourceY) / cropHeight * size.height
            val guideRadius = brush / 2f / cropWidth * size.width
            drawCircle(if (eraser) Color(0xFF86D7FF) else Color(0xFFFFCF22),
                radius = guideRadius.coerceAtLeast(2f), center = Offset(guideX, guideY),
                style = Stroke(width = 2.dp.toPx()))
        }
        drawRoundRect(Color.Black.copy(alpha = .85f), cornerRadius = corner,
            style = Stroke(width = 5.dp.toPx()))
        drawRoundRect(if (eraser) Color(0xFF86D7FF) else Color(0xFFFFC21C),
            cornerRadius = corner, style = Stroke(width = 2.dp.toPx()))
    }
}

/**
 * Independent inpainting workspace. Main crop/rotation screen is deliberately preserved.
 * Image coordinates stay normalized even when the user zooms or pans.
 */
@Composable
internal fun ObjectRemovalPanel(
    modifier: Modifier = Modifier,
    originalWorkingImage: Bitmap,
    earlierMasks: List<List<MaskStroke>>,
    onAccept: (Bitmap, List<List<MaskStroke>>, String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelStore = remember(context) { LaMaModelStore(context) }
    val engine = remember(modelStore) { LaMaInpaintingEngine(modelStore) }
    val mobileStore = remember(context) { MobileSamModelStore(context.applicationContext) }
    val segmenter = remember(mobileStore) { MobileSamSegmenter(mobileStore) }
    DisposableEffect(segmenter) { onDispose { segmenter.clearCache() } }
    val glyphDetector = remember { OnDeviceTextGlyphDetector(context.applicationContext) }
    var mobileReady by remember { mutableStateOf(false) }
    var autoMode by remember { mutableStateOf(false) }
    var askSamInstall by remember { mutableStateOf(false) }
    LaunchedEffect(mobileStore) {
        mobileReady = mobileStore.verifyInstalled()
        autoMode = mobileReady
    }
    var installed by remember { mutableStateOf(modelStore.installed()) }
    var askDownload by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var processing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // Cap history and decode resolution so several undo states don't exhaust Android RAM.
    val working = remember(originalWorkingImage) {
        val side = maxOf(originalWorkingImage.width, originalWorkingImage.height)
        if (side <= 1280) originalWorkingImage
        else Bitmap.createScaledBitmap(originalWorkingImage,
            (originalWorkingImage.width * 1280f / side).roundToInt().coerceAtLeast(1),
            (originalWorkingImage.height * 1280f / side).roundToInt().coerceAtLeast(1), true)
    }
    var steps by remember(working) { mutableStateOf(listOf(RemovalStep(working, earlierMasks))) }
    var index by remember(working) { mutableIntStateOf(0) }
    val current = steps[index]
    val strokes = remember(working) { mutableStateListOf<MaskStroke>() }
    val redoStrokes = remember(working) { mutableStateListOf<MaskStroke>() }
    val maskActions = remember(working) { MaskActionGrouping() }
    var inProgress by remember { mutableStateOf<List<MaskPoint>>(emptyList()) }
    var brush by remember { mutableFloatStateOf(32f) }
    var eraserMode by remember { mutableStateOf(false) }
    var moveMode by remember { mutableStateOf(false) }
    var textMode by remember { mutableStateOf(false) }
    var scanningText by remember { mutableStateOf(false) }
    var glyphs by remember(working) { mutableStateOf<List<DetectedGlyph>>(emptyList()) }
    val selectedGlyphs = remember(working) { mutableStateListOf<Int>() }
    val redoGlyphs = remember(working) { mutableStateListOf<Int>() }
    var brushSizing by remember { mutableStateOf(false) }
    var cursor by remember { mutableStateOf<MaskPoint?>(null) }
    var showBefore by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var selectedMode by remember { mutableStateOf(AiEraserMode.OBJECTS) }
    var objectManualTools by remember { mutableStateOf(false) }
    val busy = processing || downloading || scanningText

    fun undo() {
        if (busy) return
        if (textMode) {
            if (selectedGlyphs.isNotEmpty()) {
                redoGlyphs.add(selectedGlyphs.removeAt(selectedGlyphs.lastIndex))
            } else if (index > 0) index--
        } else if (strokes.isNotEmpty()) {
            repeat(maskActions.undoCount().coerceAtMost(strokes.size)) {
                redoStrokes.add(strokes.removeAt(strokes.lastIndex))
            }
        } else if (index > 0) {
            index--
        }
    }
    fun redo() {
        if (busy) return
        if (textMode) {
            if (redoGlyphs.isNotEmpty()) selectedGlyphs.add(redoGlyphs.removeAt(redoGlyphs.lastIndex))
            else if (index < steps.lastIndex) index++
        } else if (redoStrokes.isNotEmpty()) {
            repeat(maskActions.redoCount().coerceAtMost(redoStrokes.size)) {
                strokes.add(redoStrokes.removeAt(redoStrokes.lastIndex))
            }
        } else if (index < steps.lastIndex) index++
    }

    if (askSamInstall) AiModelManagerDialog(onDismiss = {
        askSamInstall = false
        scope.launch {
            mobileReady = mobileStore.verifyInstalled()
            if (mobileReady) {
                autoMode = true
                objectManualTools = false
            }
        }
    })

    if (askDownload) AlertDialog(
        onDismissRequest = { askDownload = false },
        title = { Text("Установить модель LaMa?") },
        text = { Text("Модель загружается отдельно и занимает значительное место. " +
            "Загружается только файл модели, ваши фотографии никуда не отправляются. " +
            "После установки обработка доступна без интернета. Для установки нужно не менее 750 МБ свободного места.") },
        confirmButton = {
            TextButton(onClick = {
                askDownload = false
                downloading = true
                message = null
                scope.launch {
                    try {
                        modelStore.install { downloadProgress = it }
                        installed = true
                    } catch (error: Exception) {
                        message = error.message ?: "Не удалось установить модель."
                    } finally {
                        downloading = false
                    }
                }
            }) { Text("Загрузить") }
        },
        dismissButton = { TextButton(onClick = { askDownload = false }) { Text("Отмена") } },
    )

    fun scanText() {
        if (busy) return
        scanningText = true
        message = null
        selectedGlyphs.clear()
        redoGlyphs.clear()
        glyphs = emptyList()
        val source = current.bitmap
        scope.launch {
            try {
                glyphs = glyphDetector.detect(source)
                if (glyphs.isEmpty()) message = "Не удалось найти отдельные буквы."
            } catch (error: Exception) {
                message = error.message ?: "Не удалось распознать текст."
            } finally {
                scanningText = false
            }
        }
    }

    fun processSelection() {
        if (busy) return
        if (textMode && glyphs.isEmpty()) {
            scanText()
            return
        }
        val marked = if (textMode) {
            TextGlyphMask.toStrokes(
                glyphs.filter { it.id in selectedGlyphs },
                current.bitmap.width, current.bitmap.height)
        } else strokes.toList()
        if (marked.isEmpty()) return
        if (!installed) {
            askDownload = true
            return
        }
        val startStep = current
        processing = true
        message = null
        scope.launch {
            try {
                val result = engine.inpaint(startStep.bitmap, marked)
                // Retain bounded undo/redo image states and existing mask batches.
                steps = steps.take(index + 1).takeLast(3) +
                    RemovalStep(result, startStep.maskBatches + listOf(marked))
                index = steps.lastIndex
                segmenter.clearCache()
                strokes.clear()
                redoStrokes.clear()
                maskActions.clear()
                selectedGlyphs.clear()
                redoGlyphs.clear()
                glyphs = emptyList()
            } catch (error: Exception) {
                message = error.message ?: "Обработка LaMa не удалась."
            } finally {
                processing = false
            }
        }
    }

    fun selectObject(point: MaskPoint) {
        if (busy) return
        if (!mobileReady) {
            askSamInstall = true
            return
        }
        val source = current.bitmap
        processing = true
        message = null
        scope.launch {
            try {
                val selection = segmenter.segment(source, point)
                // The new strokes use exactly the same normalized coordinates
                // as the existing manual correction and LaMa inpainting engine.
                strokes.addAll(selection)
                maskActions.record(selection.size)
                redoStrokes.clear()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                message = error.message ?: "Не удалось выделить объект."
            } finally {
                processing = false
            }
        }
    }

    fun acceptResult() {
        if (busy || index == 0) return
        processing = true
        message = null
        val accepted = current
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "inpainting-overlays").apply { mkdirs() }
                    val output = File(dir, "overlay-${UUID.randomUUID()}.png")
                    val overlay = LaMaInpaintingEngine.makeOverlay(
                        accepted.bitmap, accepted.maskBatches)
                    try {
                        FileOutputStream(output).use { out ->
                            check(overlay.compress(Bitmap.CompressFormat.PNG, 100, out))
                        }
                        require(output.length() > 0L)
                        output
                    } finally {
                        overlay.recycle()
                    }
                }
                onAccept(accepted.bitmap, accepted.maskBatches, file.absolutePath)
            } catch (error: Exception) {
                message = error.message ?: "Не удалось подготовить результат."
            } finally {
                processing = false
            }
        }
    }

    // Object/manual share the original LaMa mask engine; text uses the original
    // on-device glyph detector. Unsupported AI modes remain visibly disabled.
    Column(modifier.fillMaxSize().background(Color.Black)) {
        AiEraserTopBar(
            canUndo = if (textMode) selectedGlyphs.isNotEmpty() || index > 0
                else strokes.isNotEmpty() || index > 0,
            canRedo = if (textMode) redoGlyphs.isNotEmpty() || index < steps.lastIndex
                else redoStrokes.isNotEmpty() || index < steps.lastIndex,
            canCompare = index > 0,
            busy = busy,
            onUndo = ::undo,
            onRedo = ::redo,
            onCompareHold = { showBefore = it },
        )
        val ratio = (working.width.toFloat() / working.height.coerceAtLeast(1)).coerceAtLeast(0.08f)
        // Rebuild at compact preview resolution only when the union of strokes changes.
        // The merged mask is composited exactly ONCE, so crossing lines cannot darken it.
        val selectedStrokes = strokes.toList()
        val previewStrokes = remember(selectedStrokes, inProgress, brush, textMode, eraserMode) {
            if (textMode) emptyList() else selectedStrokes +
                inProgress.takeIf { it.isNotEmpty() }?.let {
                    listOf(MaskStroke(it,
                        (brush / minOf(working.width, working.height)).coerceIn(0.001f, 0.5f),
                        erase = eraserMode))
                }.orEmpty()
        }
        val unifiedOverlay = remember(previewStrokes, working.width, working.height) {
            UnifiedMaskOverlay.render(previewStrokes, working.width, working.height)
        }
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            val previewWidth = minOf(maxWidth, maxHeight * ratio)
            val previewHeight = previewWidth / ratio
        Box(
            Modifier.width(previewWidth).height(previewHeight).clipToBounds()
                .background(Color.Black).onSizeChanged { viewport = it }
                .pointerInput(moveMode, brush, busy, showBefore, textMode, glyphs, eraserMode,
                    autoMode, selectedMode, current.bitmap) {
                    if (busy || showBefore) return@pointerInput
                    fun imagePoint(position: Offset): MaskPoint? {
                        if (viewport.width == 0 || viewport.height == 0) return null
                        val x = (position.x - pan.x - viewport.width / 2f) / zoom + viewport.width / 2f
                        val y = (position.y - pan.y - viewport.height / 2f) / zoom + viewport.height / 2f
                        if (x !in 0f..viewport.width.toFloat() ||
                            y !in 0f..viewport.height.toFloat()) return null
                        return MaskPoint(
                            (x / viewport.width).coerceIn(0f, 1f),
                            (y / viewport.height).coerceIn(0f, 1f))
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val automaticSelection = !moveMode && !textMode &&
                            selectedMode == AiEraserMode.OBJECTS && autoMode
                        var autoCandidate = automaticSelection
                        val autoPoint = imagePoint(down.position)
                        val downPosition = down.position
                        var drawing = !moveMode && !textMode && !automaticSelection
                        var selecting = !moveMode && textMode && glyphs.isNotEmpty()
                        val points = mutableListOf<MaskPoint>()
                        var previousSelectionPoint = imagePoint(down.position)
                        if (drawing) {
                            imagePoint(down.position)?.let {
                                points.add(it)
                                cursor = it
                            }
                        }
                        if (selecting) {
                            previousSelectionPoint?.let { position ->
                                glyphs.filter { it.contains(position.x, position.y) }.forEach { glyph ->
                                    if (glyph.id !in selectedGlyphs && selectedGlyphs.size < 160) {
                                        selectedGlyphs.add(glyph.id)
                                        redoGlyphs.clear()
                                    }
                                }
                            }
                        }
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                autoCandidate = false
                                drawing = false
                                selecting = false
                                cursor = null
                                points.clear()
                                inProgress = emptyList()
                                zoom = (zoom * event.calculateZoom()).coerceIn(1f, 5f)
                                pan += event.calculatePan()
                                event.changes.forEach { it.consume() }
                            } else if (pressed.isNotEmpty()) {
                                val change = pressed.first()
                                if ((change.position - downPosition).getDistance() > 14f)
                                    autoCandidate = false
                                if (moveMode) {
                                    pan += change.position - change.previousPosition
                                } else if (drawing) {
                                    imagePoint(change.position)?.let {
                                        cursor = it
                                        if (points.size < 20_000) points.add(it)
                                    }
                                    inProgress = points.toList()
                                } else if (selecting) {
                                    imagePoint(change.position)?.let { position ->
                                        val before = previousSelectionPoint ?: position
                                        glyphs.filter {
                                            it.crosses(before.x, before.y, position.x, position.y)
                                        }.forEach { glyph ->
                                            if (glyph.id !in selectedGlyphs && selectedGlyphs.size < 160) {
                                                selectedGlyphs.add(glyph.id)
                                                redoGlyphs.clear()
                                            }
                                        }
                                        previousSelectionPoint = position
                                    }
                                }
                                change.consume()
                            }
                        } while (event.changes.any { it.pressed })
                        if (autoCandidate && autoPoint != null) {
                            selectObject(autoPoint)
                        }
                        if (drawing && points.isNotEmpty()) {
                            strokes.add(MaskStroke(points.toList(),
                                (brush / minOf(working.width, working.height)).coerceIn(0.001f, 0.5f),
                                erase = eraserMode))
                            maskActions.record(1)
                            redoStrokes.clear()
                        }
                        inProgress = emptyList()
                        cursor = null
                    }
                },
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                translationX = pan.x
                translationY = pan.y
            }) {
                val bitmap = if (showBefore) steps.first().bitmap else current.bitmap
                Image(bitmap.asImageBitmap(), null, modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds)
                if (!showBefore && !textMode && unifiedOverlay != null) {
                    Image(
                        bitmap = unifiedOverlay.asImageBitmap(),
                        contentDescription = "Полупрозрачная маска",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                }
                if (!showBefore) Canvas(Modifier.fillMaxSize()) {
                    val yellow = Color(0xFFFFCF22)
                    if (textMode) {
                        for (glyph in glyphs) {
                            val selected = glyph.id in selectedGlyphs
                            val topLeft = Offset(glyph.left * size.width, glyph.top * size.height)
                            val rectSize = Size(
                                (glyph.right - glyph.left) * size.width,
                                (glyph.bottom - glyph.top) * size.height,
                            )
                            if (selected) drawRect(Color(0x57FFD500), topLeft, rectSize)
                            drawRect(
                                if (selected) yellow else Color(0xBBE9F0FF),
                                topLeft = topLeft, size = rectSize,
                                style = Stroke(width = if (selected) 2f else 1.2f),
                            )
                        }
                    } else {
                        // The marker follows the same pixel-to-preview transform as the mask.
                        // It is a guide, not a new translucent paint stroke.
                        val previewScale =
                            minOf(size.width / working.width, size.height / working.height)
                        val radius = (brush * previewScale / 2f).coerceAtLeast(1f)
                        val guide = cursor ?: if (brushSizing) MaskPoint(0.5f, 0.5f) else null
                        guide?.let {
                            val center = Offset(it.x * size.width, it.y * size.height)
                            val guideColor = if (eraserMode) Color(0xFF86D7FF) else yellow
                            drawCircle(guideColor.copy(alpha = 0.28f), radius, center)
                            drawCircle(Color(0xFF312700), radius + 1.5f, center,
                                style = Stroke(width = 1.2f))
                            drawCircle(guideColor, radius, center, style = Stroke(width = 1.8f))
                        }
                    }
                }

            }
            if (!showBefore && !textMode && !moveMode && cursor != null) {
                val position = requireNotNull(cursor)
                val corner = when {
                    position.x < 0.5f && position.y < 0.5f -> Alignment.BottomEnd
                    position.x < 0.5f -> Alignment.TopEnd
                    position.y < 0.5f -> Alignment.BottomStart
                    else -> Alignment.TopStart
                }
                SelectionMagnifier(
                    bitmap = current.bitmap.asImageBitmap(),
                    overlay = unifiedOverlay?.asImageBitmap(),
                    point = position,
                    viewport = viewport,
                    zoom = zoom,
                    brush = brush,
                    eraser = eraserMode,
                    modifier = Modifier.align(corner).padding(8.dp),
                )
            }
        }

        }

        // The object selector is the ONLY mode with the A/hand toggle.
        if (selectedMode == AiEraserMode.OBJECTS) {
            BoxWithConstraints(
                Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Keep the action at the full-width center, even when the switch is present.
                val actionWidth = minOf(104.dp, (maxWidth - 220.dp).coerceAtLeast(68.dp))
                EraserStartButton(
                    enabled = !busy && strokes.isNotEmpty(),
                    onClick = ::processSelection,
                    width = actionWidth,
                )
                Box(Modifier.align(Alignment.CenterEnd)) {
                    AiEraserObjectSwitch(
                        enabled = !busy,
                        automatic = autoMode,
                        autoAvailable = mobileReady,
                        onAuto = {
                            if (mobileReady) {
                                autoMode = true
                                objectManualTools = false
                            } else askSamInstall = true
                        },
                        onManual = {
                            autoMode = false
                            objectManualTools = true
                        },
                    )
                }
            }
        }
        if (selectedMode == AiEraserMode.MANUAL ||
            (selectedMode == AiEraserMode.OBJECTS && objectManualTools)) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RemovalToolButton(Icons.Rounded.Brush, "Кисть", !eraserMode) {
                        eraserMode = false
                        moveMode = false
                    }
                    RemovalToolButton(ImageVector.vectorResource(R.drawable.ic_eraser),
                        "Стереть выделение", eraserMode) {
                        eraserMode = true
                        moveMode = false
                    }
                    Slider(
                        value = brush,
                        onValueChange = { brush = it; brushSizing = true },
                        onValueChangeFinished = { brushSizing = false },
                        valueRange = 8f..100f,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                    Canvas(Modifier.size(22.dp)) {
                        drawCircle(Color(0xFFFFC21C), radius =
                            (brush / 100f * size.minDimension * .42f).coerceAtLeast(2f),
                            style = Stroke(width = 2.dp.toPx()))
                    }
                }
                if (selectedMode == AiEraserMode.MANUAL) {
                    Box(Modifier.fillMaxWidth().height(48.dp),
                        contentAlignment = Alignment.Center) {
                        EraserStartButton(
                            enabled = !busy && strokes.isNotEmpty(),
                            onClick = ::processSelection,
                        )
                    }
                }
            }
        } else if (selectedMode == AiEraserMode.TEXT) {
            Box(
                Modifier.fillMaxWidth().height(68.dp),
                contentAlignment = Alignment.Center,
            ) {
                EraserStartButton(
                    enabled = !busy && (glyphs.isEmpty() || selectedGlyphs.isNotEmpty()),
                    label = if (glyphs.isEmpty()) "Найти текст" else "Начать",
                    width = if (glyphs.isEmpty()) 132.dp else 104.dp,
                    onClick = ::processSelection,
                )
            }
        }
        AiEraserModeStrip(
            selected = selectedMode,
            enabled = !busy,
            onMode = { mode ->
                selectedMode = mode
                objectManualTools = false
                if (mode == AiEraserMode.OBJECTS) autoMode = mobileReady
                textMode = mode == AiEraserMode.TEXT
                moveMode = false
                eraserMode = false
                strokes.clear()
                redoStrokes.clear()
                maskActions.clear()
                inProgress = emptyList()
                selectedGlyphs.clear()
                redoGlyphs.clear()
                glyphs = emptyList()
                message = null
            },
        )
        if (downloading || processing) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }
        message?.let {
            Text(it, color = Color(0xFFFFB4AB), fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp))
        }
        AiEraserFooter(
            canApply = !busy && index > 0,
            onCancel = onClose,
            onApply = ::acceptResult,
        )
    }
}

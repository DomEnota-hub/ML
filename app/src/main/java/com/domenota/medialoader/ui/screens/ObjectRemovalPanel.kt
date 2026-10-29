package com.domenota.medialoader.ui.screens
import com.domenota.medialoader.ui.components.AppSlider as Slider

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.Color
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
import com.domenota.medialoader.data.editor.LaMaInpaintingEngine
import com.domenota.medialoader.data.editor.LaMaModelStore
import com.domenota.medialoader.data.editor.MaskPoint
import com.domenota.medialoader.data.editor.MaskStroke
import com.domenota.medialoader.data.editor.DetectedGlyph
import com.domenota.medialoader.data.editor.OnDeviceTextGlyphDetector
import com.domenota.medialoader.data.editor.TextGlyphMask
import com.domenota.medialoader.data.editor.UnifiedMaskOverlay
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
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
        modifier = Modifier.size(48.dp).background(
            if (selected) scheme.primaryContainer else scheme.surfaceVariant, CircleShape),
    ) {
        Icon(icon, contentDescription = label,
            tint = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant)
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
    val diameter = with(LocalDensity.current) {
        minOf(112.dp.toPx(), viewport.height * 0.78f, viewport.width * 0.36f)
            .coerceAtLeast(48.dp.toPx()).toDp()
    }
    Canvas(modifier.size(diameter)) {
        // Sample the source pixels, including the current mask, around the finger.
        val cropWidth = (bitmap.width * size.width / viewport.width / zoom / 2.5f)
            .roundToInt().coerceIn(1, bitmap.width)
        val cropHeight = (bitmap.height * size.height / viewport.height / zoom / 2.5f)
            .roundToInt().coerceIn(1, bitmap.height)
        val sourceX = (point.x * bitmap.width - cropWidth / 2f).roundToInt()
            .coerceIn(0, bitmap.width - cropWidth)
        val sourceY = (point.y * bitmap.height - cropHeight / 2f).roundToInt()
            .coerceIn(0, bitmap.height - cropHeight)
        val window = Path().apply { addOval(Rect(0f, 0f, size.width, size.height)) }
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
        drawCircle(Color.Black.copy(alpha = 0.8f), radius = size.minDimension / 2f,
            style = Stroke(width = 5.dp.toPx()))
        drawCircle(if (eraser) Color(0xFF86D7FF) else Color(0xFFFFCF22),
            radius = size.minDimension / 2f - 2.dp.toPx(),
            style = Stroke(width = 2.dp.toPx()))
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
    val glyphDetector = remember { OnDeviceTextGlyphDetector(context.applicationContext) }
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
    val busy = processing || downloading || scanningText

    fun undo() {
        if (busy) return
        if (textMode) {
            if (selectedGlyphs.isNotEmpty()) {
                redoGlyphs.add(selectedGlyphs.removeAt(selectedGlyphs.lastIndex))
            } else if (index > 0) index--
        } else if (strokes.isNotEmpty()) {
            redoStrokes.add(strokes.removeAt(strokes.lastIndex))
        } else if (index > 0) {
            index--
        }
    }
    fun redo() {
        if (busy) return
        if (textMode) {
            if (redoGlyphs.isNotEmpty()) selectedGlyphs.add(redoGlyphs.removeAt(redoGlyphs.lastIndex))
            else if (index < steps.lastIndex) index++
        } else if (redoStrokes.isNotEmpty()) strokes.add(redoStrokes.removeAt(redoStrokes.lastIndex))
        else if (index < steps.lastIndex) index++
    }

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

    Column(
        modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Удаление объектов", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onClose, enabled = !busy) { Text("Назад") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !textMode, onClick = {
                textMode = false; moveMode = false; eraserMode = false; message = null
            }, label = { Text("Удалить объект") })
            FilterChip(selected = textMode, onClick = {
                textMode = true; moveMode = false; eraserMode = false; message = null
            }, label = { Text("Удалить текст") })
        }
        // Keep the mode selector visible while the tool settings scroll.
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
        Text(
            if (textMode)
                "Нажми «Найти буквы», затем проведи пальцем только по нужным символам. " +
                    "LaMa удалит выбранные буквы после подтверждения."
            else "Закрасьте лишний объект. «Ластик» стирает только выделение. " +
                "Двумя пальцами можно увеличить фото, кнопкой «Перемещать» — сдвигать его.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        if (textMode) {
            OutlinedButton(
                onClick = {
                    scanningText = true
                    message = null
                    selectedGlyphs.clear()
                    redoGlyphs.clear()
                    glyphs = emptyList()
                    val source = current.bitmap
                    scope.launch {
                        try {
                            val found = glyphDetector.detect(source)
                            glyphs = found
                            if (found.isEmpty()) {
                                message = "Отдельные буквы не найдены. Попробуй увеличить изображение или использовать кисть."
                            }
                        } catch (error: Exception) {
                            message = error.message ?: "Не удалось распознать буквы."
                        } finally {
                            scanningText = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (glyphs.isEmpty()) "Найти буквы" else "Распознать заново") }
            if (scanningText) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (glyphs.isNotEmpty()) Text(
                "Найдено букв: ${glyphs.size} · выбрано: ${selectedGlyphs.size}. " +
                    "Контуры — найденные символы, жёлтым — выбранные.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

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
        Box(
            Modifier.fillMaxWidth().aspectRatio(ratio).clipToBounds()
                .background(Color.Black).onSizeChanged { viewport = it }
                .pointerInput(moveMode, brush, busy, showBefore, textMode, glyphs, eraserMode) {
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
                        var drawing = !moveMode && !textMode
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
                        if (drawing && points.isNotEmpty()) {
                            strokes.add(MaskStroke(points.toList(),
                                (brush / minOf(working.width, working.height)).coerceIn(0.001f, 0.5f),
                                erase = eraserMode))
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

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            if (!textMode) {
                RemovalToolButton(Icons.Rounded.Brush, "Кисть", !moveMode && !eraserMode) {
                    moveMode = false; eraserMode = false
                }
                RemovalToolButton(ImageVector.vectorResource(R.drawable.ic_eraser), "Ластик", !moveMode && eraserMode) {
                    moveMode = false; eraserMode = true
                }
            }
            RemovalToolButton(Icons.Rounded.PanTool, "Перемещать", moveMode) {
                moveMode = !moveMode
            }
            RemovalToolButton(Icons.Rounded.Compare, "До / После", showBefore) {
                showBefore = !showBefore
            }
            RemovalToolButton(Icons.Rounded.ZoomOutMap, "Масштаб 1×", false) {
                zoom = 1f; pan = Offset.Zero
            }
        }
        if (!textMode) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Canvas(Modifier.size(72.dp)) {
                    // Magnified sample for human readability. The actual diameter
                    // always appears on the PHOTO while the slider is moving.
                    val radius = (brush / 100f * size.minDimension * 0.43f)
                        .coerceIn(3f, size.minDimension / 2f - 3f)
                    val sampleColor = if (eraserMode) Color(0xFF86D7FF) else Color(0xFFFFCF22)
                    drawCircle(sampleColor.copy(alpha = 0.34f), radius)
                    drawCircle(sampleColor, radius, style = Stroke(width = 2f))
                }
                Column {
                    Text("${if (eraserMode) "Ластик" else "Кисть"}: ${brush.roundToInt()} px",
                        style = MaterialTheme.typography.titleMedium)
                    Text("Образец увеличен. Точный размер виден на фото при настройке.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Slider(
                value = brush,
                onValueChange = { brush = it; brushSizing = true },
                onValueChangeFinished = { brushSizing = false },
                valueRange = 8f..100f,
                enabled = !busy,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = ::undo,
                enabled = !busy && (if (textMode) selectedGlyphs.isNotEmpty() || index > 0
                    else strokes.isNotEmpty() || index > 0)) {
                Text("Отменить")
            }
            OutlinedButton(onClick = ::redo,
                enabled = !busy && (if (textMode) redoGlyphs.isNotEmpty() || index < steps.lastIndex
                    else redoStrokes.isNotEmpty() || index < steps.lastIndex)) {
                Text("Повторить")
            }
            TextButton(
                onClick = {
                    if (textMode) { selectedGlyphs.clear(); redoGlyphs.clear() }
                    else { strokes.clear(); redoStrokes.clear(); inProgress = emptyList() }
                },
                enabled = !busy && (if (textMode) selectedGlyphs.isNotEmpty()
                    else strokes.isNotEmpty()),
            ) { Text(if (textMode) "Снять выделение" else "Очистить маску") }
        }
        if (!installed) {
            OutlinedButton(onClick = { askDownload = true }, enabled = !busy,
                modifier = Modifier.fillMaxWidth()) {
                Text("Установить LaMa для удаления объектов")
            }
        }
        if (downloading) {
            LinearProgressIndicator(progress = { downloadProgress / 100f },
                modifier = Modifier.fillMaxWidth())
            Text("Загрузка модели: $downloadProgress%")
        }
        if (processing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("LaMa обрабатывает фотографию на устройстве…")
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                val marked = if (textMode) {
                    TextGlyphMask.toStrokes(glyphs.filter { it.id in selectedGlyphs },
                        current.bitmap.width, current.bitmap.height)
                } else strokes.toList()
                if (marked.isEmpty() || (!textMode && unifiedOverlay == null)) return@Button
                val start = current
                processing = true
                message = null
                scope.launch {
                    try {
                        val result = engine.inpaint(start.bitmap, marked)
                        // Trim forward redo states, retaining a bounded history.
                        steps = (steps.take(index + 1).takeLast(3) +
                            RemovalStep(result, start.maskBatches + listOf(marked)))
                        index = steps.lastIndex
                        strokes.clear()
                        redoStrokes.clear()
                        selectedGlyphs.clear()
                        redoGlyphs.clear()
                        glyphs = emptyList()
                    } catch (error: Exception) {
                        message = error.message ?: "Обработка LaMa не удалась."
                    } finally {
                        processing = false
                    }
                }
            },
            enabled = installed && !busy && (if (textMode) selectedGlyphs.isNotEmpty()
                else strokes.isNotEmpty() && unifiedOverlay != null),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (textMode) "Удалить выбранные буквы" else "Удалить объект") }

        Button(
            onClick = {
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
                        message = error.message ?: "Не удалось подготовить копию."
                    } finally {
                        processing = false
                    }
                }
            },
            enabled = !busy && index > 0,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Применить к фото") }
        Text("Обработка локальная. Исходный файл не изменяется; сохранить результат " +
            "можно отдельной копией в редакторе.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

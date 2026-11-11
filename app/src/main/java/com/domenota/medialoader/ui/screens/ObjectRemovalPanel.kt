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
import androidx.compose.ui.res.painterResource
import com.domenota.medialoader.R
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.domenota.medialoader.data.editor.LaMaInpaintingEngine
import com.domenota.medialoader.data.editor.LaMaModelStore
import com.domenota.medialoader.data.editor.OrtSessions
import com.domenota.medialoader.data.editor.MobileSamModelStore
import com.domenota.medialoader.data.editor.MobileSamSegmenter
import com.domenota.medialoader.data.editor.PersonDetectorModelStore
import com.domenota.medialoader.data.editor.PersonDetector
import com.domenota.medialoader.data.editor.PersonSegmentationPipeline
import com.domenota.medialoader.data.editor.PersonScanResult
import com.domenota.medialoader.data.editor.PersonInstance
import com.domenota.medialoader.data.editor.PersonInstancePolicy
import com.domenota.medialoader.data.editor.PersonContourOverlay
import com.domenota.medialoader.data.editor.SpecularSrNetModelStore
import com.domenota.medialoader.data.editor.SpecularGlareEngine
import com.domenota.medialoader.data.editor.SpecularOverlayComposer
import com.domenota.medialoader.data.editor.ReflectionDExModelStore
import com.domenota.medialoader.data.editor.ReflectionRemovalEngine
import com.domenota.medialoader.data.editor.MaskPoint
import com.domenota.medialoader.data.editor.ImageViewportGeometry
import com.domenota.medialoader.data.editor.AutoDragSelectionPolicy
import com.domenota.medialoader.data.editor.ObjectManualTracePolicy
import com.domenota.medialoader.data.editor.MaskStroke
import com.domenota.medialoader.data.editor.MaskActionGrouping
import com.domenota.medialoader.data.editor.DetectedGlyph
import com.domenota.medialoader.data.editor.OnDeviceTextGlyphDetector
import com.domenota.medialoader.data.editor.UnifiedMaskOverlay
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class RemovalStep(
    val bitmap: Bitmap,
    val maskBatches: List<List<MaskStroke>>,
    val fullFrameCorrection: Boolean = false,
)

@Composable
private fun RemovalToolButton(
    iconRes: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp).background(
            if (selected) Color(0xFFFFC21C) else Color(0xFF292929),
            RoundedCornerShape(10.dp)),
    ) {
        Icon(painterResource(iconRes), contentDescription = label,
            modifier = Modifier.size(22.dp),
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
        listOf(Color(0xFFFFE99B), Color(0xFFFFC21C), Color(0xFFFFAA00))
    } else {
        listOf(Color(0xFF626262), Color(0xFF626262))
    }
    Box(
        Modifier.width(width).height(37.dp)
            .border(BorderStroke(1.dp, GradientBrush.horizontalGradient(gradient)), shape)
            .background(if (enabled) Color(0xFFFFC21C) else Color(0xFF343434), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Color(0xFF151515) else Color(0xFFC4C4C4),
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
    lensWidth: Dp,
    brush: Float,
    eraser: Boolean,
    trace: List<MaskPoint> = emptyList(),
    modifier: Modifier = Modifier,
) {
    if (viewport.width <= 0 || viewport.height <= 0) return
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
            // The same normalized source-space trace is drawn inside the lens.
            if (trace.size >= 2) {
                val trail = Path()
                trace.forEachIndexed { index, sample ->
                    val px = (sample.x * bitmap.width - sourceX) / cropWidth * size.width
                    val py = (sample.y * bitmap.height - sourceY) / cropHeight * size.height
                    if (index == 0) trail.moveTo(px, py) else trail.lineTo(px, py)
                }
                drawPath(trail, Color(0xFFFFCF22),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }
            val guideX = (point.x * bitmap.width - sourceX) / cropWidth * size.width
            val guideY = (point.y * bitmap.height - sourceY) / cropHeight * size.height
            val guideRadius = if (trace.isNotEmpty()) 2.dp.toPx()
                else brush / 2f / cropWidth * size.width
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
    earlierFullFrameCorrection: Boolean = false,
    earlierOverlayPath: String? = null,
    onAccept: (Bitmap, List<List<MaskStroke>>, String, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelStore = remember(context) { LaMaModelStore(context) }
    val engine = remember(modelStore) { LaMaInpaintingEngine(modelStore) }
    // The preview owns one editor lease. Preloading happens on this screen's
    // coroutine scope, not a detached global job, and is cancelled on exit.
    DisposableEffect(modelStore, context.applicationContext) {
        OrtSessions.attachEditor(context.applicationContext)
        val preloading = scope.launch(Dispatchers.IO) {
            try {
                if (modelStore.installed()) {
                    val verified = modelStore.requireInstalled()
                    OrtSessions.lama.warmUp(verified, LaMaModelStore.SHA256)
                }
            } catch (_: Exception) {
                // Optional warm-up must not block painting or hide real inference errors.
            }
        }
        onDispose {
            preloading.cancel()
            OrtSessions.detachEditor()
        }
    }
    val glareStore = remember(context) { SpecularSrNetModelStore(context.applicationContext) }
    val glareEngine = remember(glareStore) { SpecularGlareEngine(glareStore) }
    var glareReady by remember { mutableStateOf(false) }
    var askGlareInstall by remember { mutableStateOf(false) }
    val reflectionStore = remember(context) { ReflectionDExModelStore(context.applicationContext) }
    val reflectionEngine = remember(reflectionStore) { ReflectionRemovalEngine(reflectionStore) }
    var reflectionReady by remember { mutableStateOf(false) }
    var askReflectionInstall by remember { mutableStateOf(false) }
    var reflectionStrength by remember { mutableFloatStateOf(.75f) }
    val mobileStore = remember(context) { MobileSamModelStore(context.applicationContext) }
    val segmenter = remember(mobileStore) { MobileSamSegmenter(mobileStore) }
    val peopleStore = remember(context) { PersonDetectorModelStore(context.applicationContext) }
    val detector = remember(peopleStore) { PersonDetector(context.applicationContext, peopleStore) }
    val peoplePipeline = remember(detector, segmenter) {
        PersonSegmentationPipeline(detector, segmenter)
    }
    var peopleReady by remember { mutableStateOf(false) }
    var peopleSearching by remember { mutableStateOf(false) }
    var askPeopleInstall by remember { mutableStateOf(false) }
    var detectedPeople by remember { mutableStateOf<List<PersonInstance>>(emptyList()) }
    val selectedPeople = remember { mutableStateListOf<Int>() }
    var ambiguousPeople by remember { mutableStateOf<Set<Int>>(emptySet()) }
    DisposableEffect(segmenter) { onDispose { segmenter.clearCache() } }
    val glyphDetector = remember { OnDeviceTextGlyphDetector(context.applicationContext) }
    var mobileReady by remember { mutableStateOf(false) }
    var autoMode by remember { mutableStateOf(false) }
    var askSamInstall by remember { mutableStateOf(false) }
    LaunchedEffect(mobileStore) {
        mobileReady = mobileStore.verifyInstalled()
        peopleReady = peopleStore.verifyInstalled()
        glareReady = glareStore.verifyInstalled()
        reflectionReady = reflectionStore.verifyInstalled()
        autoMode = mobileReady
    }
    var installed by remember { mutableStateOf(modelStore.installed()) }
    var askDownload by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var processing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var featureNotice by remember { mutableStateOf<String?>(null) }

    // Cap history and decode resolution so several undo states don't exhaust Android RAM.
    val working = remember(originalWorkingImage) {
        val side = maxOf(originalWorkingImage.width, originalWorkingImage.height)
        if (side <= 1280) originalWorkingImage
        else Bitmap.createScaledBitmap(originalWorkingImage,
            (originalWorkingImage.width * 1280f / side).roundToInt().coerceAtLeast(1),
            (originalWorkingImage.height * 1280f / side).roundToInt().coerceAtLeast(1), true)
    }
    var steps by remember(working) { mutableStateOf(listOf(RemovalStep(working, earlierMasks,
        fullFrameCorrection = earlierFullFrameCorrection))) }
    var index by remember(working) { mutableIntStateOf(0) }
    val current = steps[index]
    val strokes = remember(working) { mutableStateListOf<MaskStroke>() }
    val redoStrokes = remember(working) { mutableStateListOf<MaskStroke>() }
    val maskActions = remember(working) { MaskActionGrouping() }
    var inProgress by remember { mutableStateOf<List<MaskPoint>>(emptyList()) }
    var autoTrace by remember { mutableStateOf<List<MaskPoint>>(emptyList()) }
    var objectTrace by remember { mutableStateOf<List<MaskPoint>>(emptyList()) }
    var segmenting by remember { mutableStateOf(false) }
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
    var lastBrushPoint by remember { mutableStateOf<MaskPoint?>(null) }
    var showBefore by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var selectedMode by remember { mutableStateOf(AiEraserMode.OBJECTS) }
    var refiningPeople by remember { mutableStateOf(false) }
    val busy = processing || downloading || scanningText || peopleSearching

    // Preview the exact source-pixel mask BEFORE launching LaMa. This is
    // calculated away from the Compose drawing thread and is never a rectangle.
    var safeTextPreview by remember(working) {
        mutableStateOf<List<MaskStroke>>(emptyList())
    }
    val textSelectionSnapshot = selectedGlyphs.toList()
    LaunchedEffect(textMode, current.bitmap, glyphs, textSelectionSnapshot) {
        safeTextPreview = emptyList()
        if (textMode && textSelectionSnapshot.isNotEmpty()) {
            val selected = glyphs.filter { it.id in textSelectionSnapshot }
            safeTextPreview = withContext(Dispatchers.Default) {
                com.domenota.medialoader.data.editor.GlyphContentMask.fromBitmap(
                    current.bitmap, selected)
            }
        }
    }

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

    if (askSamInstall || askPeopleInstall || askGlareInstall || askReflectionInstall) AiModelManagerDialog(onDismiss = {
        askSamInstall = false
        askPeopleInstall = false
        askGlareInstall = false
        askReflectionInstall = false
        scope.launch {
            mobileReady = mobileStore.verifyInstalled()
            peopleReady = peopleStore.verifyInstalled()
            glareReady = glareStore.verifyInstalled()
            reflectionReady = reflectionStore.verifyInstalled()
            if (mobileReady && selectedMode == AiEraserMode.OBJECTS) {
                autoMode = true
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
                val found = glyphDetector.detect(source)
                glyphs = found
                // Detection does not erase anything. Select OCR candidates by
                // default so "Начать" is usable immediately after a scan;
                // the actual pixel mask is still validated before inpainting.
                selectedGlyphs.addAll(found.map { it.id }.distinct().take(160))
                if (found.isEmpty()) message = "Не удалось найти отдельные буквы."
            } catch (error: Exception) {
                message = error.message ?: "Не удалось распознать текст."
            } finally {
                scanningText = false
            }
        }
    }

    fun findPeople() {
        if (busy) return
        if (!peopleReady || !mobileReady) {
            askPeopleInstall = true
            message = "Для поиска людей установите EfficientDet-Lite0 и MobileSAM в «AI-модели»."
            return
        }
        val source = current.bitmap
        peopleSearching = true
        message = null
        detectedPeople = emptyList()
        selectedPeople.clear()
        ambiguousPeople = emptySet()
        scope.launch {
            try {
                val found = peoplePipeline.scan(source)
                detectedPeople = found.people
                ambiguousPeople = found.ambiguousIds
                message = when {
                    found.detectedCount == 0 -> "Люди не найдены на фотографии."
                    found.people.isEmpty() -> "Люди обнаружены, но точные маски получить не удалось."
                    found.ambiguousIds.isNotEmpty() ->
                        "Некоторые люди перекрываются. Пересекающиеся маски заблокированы для безопасности."
                    found.unsuccessful > 0 ->
                        "Некоторые люди найдены, но не все маски получены. Выберите доступные."
                    else -> "Выберите одного или нескольких людей, затем нажмите «Начать»."
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                message = error.message ?: "Не удалось выполнить поиск людей."
            } finally {
                peopleSearching = false
            }
        }
    }

    // Automatically detect when opening People mode if both verified models
    // are available. This effect is keyed to the photo/mode, never to the
    // result list, so completion cannot restart an infinite search cycle.
    LaunchedEffect(selectedMode, current.bitmap, peopleReady, mobileReady) {
        if (selectedMode == AiEraserMode.PEOPLE && peopleReady && mobileReady &&
            !peopleSearching && !processing) findPeople()
    }

    fun processSelection() {
        if (busy) return
        if (textMode && glyphs.isEmpty()) {
            scanText()
            return
        }
        val chosenGlyphs = if (textMode) glyphs.filter { it.id in selectedGlyphs }
            else emptyList()
        val otherMasks = when {
            textMode -> emptyList()
            selectedMode == AiEraserMode.PEOPLE -> detectedPeople
                .filter { it.detection.id in selectedPeople && it.detection.id !in ambiguousPeople }
                .flatMap { it.strokes } + strokes.toList()
            else -> strokes.toList()
        }
        if (if (textMode) chosenGlyphs.isEmpty() else otherMasks.isEmpty()) return
        if (!installed) {
            askDownload = true
            return
        }
        val startStep = current
        processing = true
        message = null
        scope.launch {
            try {
                // Never use OCR bounding rectangles as the final LaMa mask.
                // Pixel refinement is done off the UI thread; uncertain glyphs
                // fail closed so the user can correct them manually.
                val marked = if (textMode) withContext(Dispatchers.Default) {
                    com.domenota.medialoader.data.editor.GlyphContentMask.fromBitmap(
                        startStep.bitmap, chosenGlyphs)
                } else otherMasks
                if (marked.isEmpty()) {
                    // No bounding-box fallback: it could remove real scenery.
                    // Give the user an actionable manual correction path instead
                    // of leaving "Начать" apparently broken with no way forward.
                    if (textMode) {
                        selectedMode = AiEraserMode.MANUAL
                        textMode = false
                        selectedGlyphs.clear()
                        redoGlyphs.clear()
                        glyphs = emptyList()
                        message = "Точная маска текста не подтверждена. " +
                            "Открылся ручной режим: отметьте буквы кистью и нажмите «Начать»."
                    } else {
                        message = "Выделите область для удаления."
                    }
                    return@launch
                }
                val result = engine.inpaint(startStep.bitmap, marked)
                // Retain bounded undo/redo image states and existing mask batches.
                steps = steps.take(index + 1).takeLast(3) +
                    RemovalStep(result, startStep.maskBatches + listOf(marked),
                        fullFrameCorrection = startStep.fullFrameCorrection)
                index = steps.lastIndex
                segmenter.clearCache()
                strokes.clear()
                redoStrokes.clear()
                maskActions.clear()
                selectedGlyphs.clear()
                redoGlyphs.clear()
                glyphs = emptyList()
                detectedPeople = emptyList()
                selectedPeople.clear()
                ambiguousPeople = emptySet()
            } catch (error: Exception) {
                message = error.message ?: "Обработка LaMa не удалась."
            } finally {
                processing = false
            }
        }
    }

    fun processGlare() {
        if (busy) return
        if (!glareReady) {
            askGlareInstall = true
            message = "Для коррекции бликов установите SRNet через «AI-модели» (230 КБ, без интернета)."
            return
        }
        val startStep = current
        processing = true
        message = null
        scope.launch {
            try {
                val result = glareEngine.correct(startStep.bitmap)
                // Unlike LaMa, this is a FULL-FRAME neural photometric correction.
                // Export the corrected bitmap with opaque alpha; an empty LaMa mask
                // would silently drop this result on photo export.
                steps = steps.take(index + 1).takeLast(3) +
                    RemovalStep(result, startStep.maskBatches, fullFrameCorrection = true)
                index = steps.lastIndex
                segmenter.clearCache()
                strokes.clear()
                redoStrokes.clear()
                maskActions.clear()
                selectedGlyphs.clear()
                redoGlyphs.clear()
                glyphs = emptyList()
                detectedPeople = emptyList()
                selectedPeople.clear()
                ambiguousPeople = emptySet()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                message = error.message ?: "SRNet не смогла убрать блик."
            } finally {
                processing = false
            }
        }
    }

    fun processReflection() {
        if (busy) return
        if (!reflectionReady) {
            askReflectionInstall = true
            message = "Импортируйте проверенную DExNet + VGG19 через «AI-модели» (91 МБ, без сети)."
            return
        }
        val startStep = current
        val requestedStrength = reflectionStrength
        processing = true
        message = null
        scope.launch {
            try {
                val result = reflectionEngine.remove(startStep.bitmap, requestedStrength)
                // DExNet predicts actual transmitted scene pixels, not an LaMa mask.
                // Existing transparent overlay compositor preserves original detail
                // outside the neural correction for export and re-entry.
                steps = steps.take(index + 1).takeLast(3) +
                    RemovalStep(result, startStep.maskBatches, fullFrameCorrection = true)
                index = steps.lastIndex
                segmenter.clearCache()
                strokes.clear()
                redoStrokes.clear()
                maskActions.clear()
                selectedGlyphs.clear()
                redoGlyphs.clear()
                glyphs = emptyList()
                detectedPeople = emptyList()
                selectedPeople.clear()
                ambiguousPeople = emptySet()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                message = error.message ?: "DExNet не смогла убрать отражение."
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
        segmenting = true
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
                segmenting = false
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
                    val overlay = if (accepted.fullFrameCorrection) {
                        // Never export an opaque preview: the full-resolution
                        // original must survive outside the corrected pixels.
                        val previous = earlierOverlayPath?.let { oldPath ->
                            val permitted = File(context.cacheDir, "inpainting-overlays").canonicalFile
                            val input = File(oldPath).canonicalFile
                            if (input.parentFile != permitted || !input.isFile ||
                                !input.name.endsWith(".png"))
                                throw java.io.IOException("Недопустимый путь предыдущей маски.")
                            BitmapFactory.decodeFile(input.absolutePath)
                                ?: throw java.io.IOException("Не удалось прочитать прежнюю маску.")
                        }
                        try {
                            SpecularOverlayComposer.combine(
                                steps.first().bitmap, accepted.bitmap, previous)
                        } finally {
                            previous?.recycle()
                        }
                    } else LaMaInpaintingEngine.makeOverlay(
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
                onAccept(accepted.bitmap, accepted.maskBatches, file.absolutePath,
                    accepted.fullFrameCorrection)
            } catch (error: Exception) {
                message = error.message ?: "Не удалось подготовить результат."
            } finally {
                processing = false
            }
        }
    }

    // Object/manual share the original LaMa mask engine; text uses the original
    // on-device glyph detector. Unsupported AI modes remain visibly disabled.
    val noticeHost = remember { SnackbarHostState() }
    LaunchedEffect(message, featureNotice) {
        val notice = message ?: featureNotice
        if (!notice.isNullOrBlank()) {
            // Real errors should remain actionable but never permanently
            // displace the controls and shrink the working photo.
            noticeHost.showSnackbar(notice, duration = SnackbarDuration.Short,
                withDismissAction = true)
            if (message == notice) message = null
            if (featureNotice == notice) featureNotice = null
        }
    }
    Box(modifier.fillMaxSize().background(Color.Black)) {
    Column(Modifier.fillMaxSize()) {
        AiEraserTopBar(
            canUndo = if (textMode) selectedGlyphs.isNotEmpty() || index > 0
                else strokes.isNotEmpty() || index > 0,
            canRedo = if (textMode) redoGlyphs.isNotEmpty() || index < steps.lastIndex
                else redoStrokes.isNotEmpty() || index < steps.lastIndex,
            canCompare = index > 0,
            canResetView = zoom != 1f || pan != Offset.Zero,
            busy = busy,
            onUndo = ::undo,
            onRedo = ::redo,
            onCompareHold = { showBefore = it },
            onResetView = {
                zoom = 1f
                pan = Offset.Zero
                cursor = null
                brushSizing = false
            },
        )
        val ratio = (working.width.toFloat() / working.height.coerceAtLeast(1)).coerceAtLeast(0.08f)
        // Rebuild at compact preview resolution only when the union of strokes changes.
        // The merged mask is composited exactly ONCE, so crossing lines cannot darken it.
        val selectedStrokes = if (selectedMode == AiEraserMode.PEOPLE) detectedPeople
            .filter { it.detection.id in selectedPeople && it.detection.id !in ambiguousPeople }
            .flatMap { it.strokes } + strokes.toList() else strokes.toList()
        val previewStrokes = remember(selectedStrokes, inProgress, brush, textMode, eraserMode,
            selectedMode) {
            // The unified preview contains the actual person-mask silhouette
            // plus all manual additions/CLEAR strokes in source coordinates.
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
        val peopleContour = remember(detectedPeople, ambiguousPeople,
            working.width, working.height) {
            // Outline-only people behind the merged yellow preview. The latter
            // shows EXACTLY what LaMa will receive after brush/eraser corrections.
            PersonContourOverlay.render(detectedPeople, emptySet(), ambiguousPeople,
                working.width, working.height)
        }
        DisposableEffect(peopleContour) {
            onDispose { peopleContour?.recycle() }
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
                    autoMode, selectedMode, refiningPeople, current.bitmap) {
                    if (busy || showBefore) return@pointerInput
                    fun imagePoint(position: Offset): MaskPoint? =
                        ImageViewportGeometry.viewportToImage(
                            position.x, position.y,
                            viewport.width.toFloat(), viewport.height.toFloat(),
                            zoom, pan.x, pan.y,
                        )
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val automaticSelection = !moveMode && !textMode &&
                            selectedMode == AiEraserMode.OBJECTS && autoMode
                        var autoDrawing = automaticSelection
                        var maxTravelPx = 0f
                        val autoPoints = mutableListOf<MaskPoint>()
                        val downPosition = down.position
                        if (autoDrawing) {
                            imagePoint(down.position)?.let {
                                autoPoints.add(it)
                                cursor = it
                                autoTrace = autoPoints.toList()
                            }
                        }
                        val manualObject = selectedMode == AiEraserMode.OBJECTS && !autoMode
                        var drawing = !moveMode && !textMode && !automaticSelection &&
                            (selectedMode != AiEraserMode.PEOPLE ||
                                (refiningPeople && selectedPeople.isNotEmpty())) &&
                            selectedMode != AiEraserMode.GLARE &&
                            selectedMode != AiEraserMode.REFLECTION
                        var selecting = !moveMode && textMode && glyphs.isNotEmpty()
                        var selectionDrag = false
                        var personTap = !moveMode && selectedMode == AiEraserMode.PEOPLE &&
                            !refiningPeople
                        val points = mutableListOf<MaskPoint>()
                        var previousSelectionPoint = imagePoint(down.position)
                        if (drawing) {
                            imagePoint(down.position)?.let {
                                points.add(it)
                                cursor = it
                                if (selectedMode == AiEraserMode.MANUAL ||
                                    selectedMode == AiEraserMode.PEOPLE) lastBrushPoint = it
                                if (manualObject) objectTrace = points.toList()
                            }
                        }
                        // Tapping toggles a glyph; dragging only adds glyphs
                        // intersected by the finger trail. Multi-touch zoom
                        // cancels selection and never edits the mask.
                        try {
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                autoDrawing = false
                                personTap = false
                                autoPoints.clear()
                                autoTrace = emptyList()
                                drawing = false
                                selecting = false
                                cursor = null
                                points.clear()
                                inProgress = emptyList()
                                objectTrace = emptyList()
                                zoom = (zoom * event.calculateZoom()).coerceIn(1f, 5f)
                                pan += event.calculatePan()
                                event.changes.forEach { it.consume() }
                            } else if (pressed.isNotEmpty()) {
                                val change = pressed.first()
                                if (moveMode) {
                                    pan += change.position - change.previousPosition
                                } else if (autoDrawing) {
                                    maxTravelPx = maxOf(maxTravelPx,
                                        (change.position - downPosition).getDistance())
                                    imagePoint(change.position)?.let { position ->
                                        cursor = position
                                        if (autoPoints.size < 20_000) autoPoints.add(position)
                                        autoTrace = autoPoints.toList()
                                    }
                                } else if (drawing) {
                                    imagePoint(change.position)?.let {
                                        cursor = it
                                        if (selectedMode == AiEraserMode.MANUAL ||
                                            selectedMode == AiEraserMode.PEOPLE) lastBrushPoint = it
                                        if (points.size < 20_000) points.add(it)
                                    }
                                    if (manualObject) objectTrace = points.toList()
                                    else inProgress = points.toList()
                                } else if (selecting) {
                                    if ((change.position - downPosition).getDistance() >
                                        10.dp.toPx()) selectionDrag = true
                                    imagePoint(change.position)?.let { position ->
                                        val before = previousSelectionPoint ?: position
                                        if (selectionDrag) glyphs.filter {
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
                            } else if (drawing && manualObject) {
                                // Include the finger-up sample even when no preceding move arrives.
                                event.changes.firstOrNull()?.let { release ->
                                    imagePoint(release.position)?.let { point ->
                                        if (points.size < 20_000) points.add(point)
                                    }
                                }
                            } else if (autoDrawing) {
                                // Capture the actual release position; the final motion event
                                // can contain a finger-up without an intermediate pressed move.
                                event.changes.firstOrNull()?.let { release ->
                                    maxTravelPx = maxOf(maxTravelPx,
                                        (release.position - downPosition).getDistance())
                                    imagePoint(release.position)?.let { point ->
                                        if (autoPoints.size < 20_000) autoPoints.add(point)
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        if (selecting && !selectionDrag) {
                            imagePoint(downPosition)?.let { point ->
                                glyphs.firstOrNull { it.contains(point.x, point.y) }?.let { glyph ->
                                    if (!selectedGlyphs.remove(glyph.id) && selectedGlyphs.size < 160)
                                        selectedGlyphs.add(glyph.id)
                                    redoGlyphs.clear()
                                }
                            }
                        }
                        if (personTap) {
                            val tapped = imagePoint(downPosition)
                            val candidate = tapped?.let { point ->
                                detectedPeople.filter {
                                    it.detection.id !in ambiguousPeople &&
                                        PersonInstancePolicy.containsMask(
                                            it, point, current.bitmap.width, current.bitmap.height)
                                }.minByOrNull { it.detection.box.width * it.detection.box.height }
                            }
                            candidate?.detection?.id?.let { id ->
                                if (!selectedPeople.remove(id)) selectedPeople.add(id)
                                // Corrections always belong to the previous
                                // selection; never silently apply to a new person.
                                strokes.clear()
                                redoStrokes.clear()
                                maskActions.clear()
                            }
                        }
                        // Tap alone is not an opaque "AI selected something" action.
                        val prompt = if (autoDrawing) AutoDragSelectionPolicy.releasePrompt(
                            autoPoints, maxTravelPx, 14.dp.toPx()) else null
                        if (prompt != null) {
                            selectObject(prompt)
                        } else if (autoDrawing) {
                            message = "Проведите линию по объекту, затем отпустите палец."
                        }
                        if (drawing && points.isNotEmpty()) {
                            // OBJECTS/hand is a fixed, narrow source-space LaMa mask.
                            // It NEVER calls MobileSAM and has no adjustable brush/eraser.
                            val stroke = if (manualObject) {
                                ObjectManualTracePolicy.toStroke(
                                    points, working.width, working.height)
                            } else MaskStroke(points.toList(),
                                (brush / minOf(working.width, working.height)).coerceIn(0.001f, 0.5f),
                                erase = eraserMode)
                            if (stroke != null) {
                                strokes.add(stroke)
                                maskActions.record(1)
                                redoStrokes.clear()
                            }
                        }
                        } finally {
                            // Also runs when Android cancels a pointer gesture.
                            inProgress = emptyList()
                            autoTrace = emptyList()
                            objectTrace = emptyList()
                            cursor = null
                        }
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
                if (!showBefore && selectedMode == AiEraserMode.PEOPLE &&
                    peopleContour != null) {
                    Image(peopleContour.asImageBitmap(),
                        contentDescription = "Контуры найденных людей",
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                }
                if (!showBefore && !textMode && unifiedOverlay != null) {
                    Image(
                        bitmap = unifiedOverlay.asImageBitmap(),
                        contentDescription = "Полупрозрачная маска с исправлениями",
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
                            // OCR boxes are just candidate guides; the actual
                            // removal mask is the pixel trace rendered below.
                            // When the true pixel mask exists, do not leave
                            // misleading yellow rectangles on top of letters.
                            if (!selected || safeTextPreview.isEmpty()) {
                                drawRect(
                                    if (selected) yellow else Color(0xBBE9F0FF),
                                    topLeft = topLeft, size = rectSize,
                                    style = Stroke(width = if (selected) 2f else 1.2f),
                                )
                            }
                        }
                        for (line in safeTextPreview) {
                            val sizePx = line.diameter * minOf(size.width, size.height)
                            val points = line.points
                            if (points.size == 1) {
                                drawCircle(yellow, sizePx / 2f,
                                    Offset(points[0].x * size.width, points[0].y * size.height))
                            } else if (points.size > 1) {
                                drawLine(yellow,
                                    Offset(points.first().x * size.width,
                                        points.first().y * size.height),
                                    Offset(points.last().x * size.width,
                                        points.last().y * size.height),
                                    strokeWidth = sizePx.coerceAtLeast(1f),
                                    cap = StrokeCap.Round)
                            }
                        }
                    } else {
                        // Live traces are hints only; the committed manual path becomes
                        // a real narrow LaMa mask, while auto prompts MobileSAM on release.
                        val visibleTrace = if (selectedMode == AiEraserMode.OBJECTS && !autoMode)
                            objectTrace else autoTrace
                        if (visibleTrace.size >= 2 &&
                            selectedMode == AiEraserMode.OBJECTS) {
                            val trail = Path()
                            visibleTrace.forEachIndexed { i, p ->
                                val x = p.x * size.width
                                val y = p.y * size.height
                                if (i == 0) trail.moveTo(x, y) else trail.lineTo(x, y)
                            }
                            drawPath(trail, yellow,
                                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                        }
                        // The marker follows the same pixel-to-preview transform as the mask.
                        // It is a guide, not a new translucent paint stroke.
                        val previewScale =
                            minOf(size.width / working.width, size.height / working.height)
                        val radius = if (selectedMode == AiEraserMode.OBJECTS)
                            2.dp.toPx() else (brush * previewScale / 2f).coerceAtLeast(1f)
                        // A size-slider gesture previews the diameter in the
                        // center of the *visible* image viewport, not at a
                        // stale touch point (which may be offscreen after zoom).
                        // This uses the same zoom/pan inverse as real painting.
                        val guide = if (brushSizing && viewport.width > 0 &&
                            viewport.height > 0) {
                            ImageViewportGeometry.viewportToImage(
                                viewport.width / 2f, viewport.height / 2f,
                                viewport.width.toFloat(), viewport.height.toFloat(),
                                zoom, pan.x, pan.y)
                        } else cursor
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
            if (segmenting || peopleSearching || scanningText || processing) {
                Box(
                    Modifier.align(Alignment.Center)
                        .background(Color.Black.copy(alpha = .58f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AiPrismWorkingIndicator(
                        label = when {
                            peopleSearching -> "Ищем людей и уточняем контуры…"
                            segmenting -> "Уточняем контур…"
                            scanningText -> "Распознаём текст…"
                            else -> "Обрабатываем изображение…"
                        },
                    )
                }
            }
            if (!showBefore && !textMode && !moveMode && cursor != null &&
                (selectedMode != AiEraserMode.PEOPLE || refiningPeople) &&
                selectedMode != AiEraserMode.GLARE &&
                selectedMode != AiEraserMode.REFLECTION) {
                val position = requireNotNull(cursor)
                // Use the exact inverse of the pointer-to-image transform, including
                // zoom and pan, to place the lens relative to the REAL touch point.
                val finger = ImageViewportGeometry.imageToViewport(
                    position, viewport.width.toFloat(), viewport.height.toFloat(),
                    zoom, pan.x, pan.y)
                val density = LocalDensity.current
                val lens = finger?.let {
                    ImageViewportGeometry.placeMagnifier(
                        finger = it,
                        viewportWidth = viewport.width.toFloat(),
                        viewportHeight = viewport.height.toFloat(),
                        maxWidth = with(density) { 152.dp.toPx() },
                        inset = with(density) { 8.dp.toPx() },
                        clearance = with(density) { 24.dp.toPx() },
                    )
                }
                if (lens != null) {
                    SelectionMagnifier(
                        bitmap = current.bitmap.asImageBitmap(),
                        overlay = unifiedOverlay?.asImageBitmap(),
                        point = position,
                        viewport = viewport,
                        zoom = zoom,
                        lensWidth = with(density) { lens.width.toDp() },
                        brush = if (selectedMode == AiEraserMode.OBJECTS) 4f else brush,
                        eraser = eraserMode && selectedMode == AiEraserMode.MANUAL,
                        trace = if (selectedMode == AiEraserMode.OBJECTS && !autoMode)
                            objectTrace else autoTrace,
                        modifier = Modifier.offset {
                            IntOffset(lens.left.roundToInt(), lens.top.roundToInt())
                        },
                    )
                }
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
                                objectTrace = emptyList()
                            } else askSamInstall = true
                        },
                        onManual = {
                            autoMode = false
                            eraserMode = false
                            brushSizing = false
                            inProgress = emptyList()
                            autoTrace = emptyList()
                        },
                    )
                }
            }
        }
        // Only the separate MANUAL mode has brush, eraser and width controls.
        // The hand toggle within OBJECTS draws a fixed thin yellow path.
        if (selectedMode == AiEraserMode.MANUAL) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RemovalToolButton(R.drawable.ic_ai_eraser_brush_tool, "Кисть", !eraserMode) {
                        eraserMode = false
                        moveMode = false
                    }
                    RemovalToolButton(R.drawable.ic_ai_eraser_erase_tool,
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
                        retainColorWhenDisabled = true,
                        modifier = Modifier.weight(1f),
                    )
                    // A small unrelated circle cannot represent a zoomed
                    // brush. Show exact source-pixel diameter numerically;
                    // the real on-photo guide above is scale-aware.
                    Text("Ø ${brush.roundToInt()} px",
                        modifier = Modifier.width(56.dp),
                        fontSize = 10.sp,
                        color = Color(0xFFFFC21C),
                        maxLines = 1)

                }
                Box(Modifier.fillMaxWidth().height(48.dp),
                    contentAlignment = Alignment.Center) {
                    EraserStartButton(
                        enabled = !busy && strokes.isNotEmpty(),
                        onClick = ::processSelection,
                    )
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
        if (selectedMode == AiEraserMode.GLARE) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth().height(48.dp),
                    contentAlignment = Alignment.Center) {
                    EraserStartButton(
                        enabled = !busy,
                        label = "Начать",
                        onClick = ::processGlare,
                    )
                }
            }
        }
        if (selectedMode == AiEraserMode.REFLECTION) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Сила удаления отражения: ${(reflectionStrength * 100).roundToInt()}%",
                    fontSize = 11.sp, color = Color.White)
                Slider(
                    value = reflectionStrength,
                    onValueChange = { reflectionStrength = it },
                    valueRange = .10f..1f,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(Modifier.fillMaxWidth().height(48.dp),
                    contentAlignment = Alignment.Center) {
                    EraserStartButton(enabled = !busy, label = "Начать",
                        onClick = ::processReflection)
                }
            }
        }
        if (selectedMode == AiEraserMode.PEOPLE) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.fillMaxWidth().height(48.dp),
                    contentAlignment = Alignment.Center) {
                    EraserStartButton(
                        enabled = !busy && (detectedPeople.isEmpty() ||
                            selectedPeople.isNotEmpty()),
                        label = if (detectedPeople.isEmpty()) "Найти людей" else "Начать",
                        width = if (detectedPeople.isEmpty()) 132.dp else 104.dp,
                        onClick = if (detectedPeople.isEmpty()) ::findPeople else ::processSelection,
                    )
                }
                if (detectedPeople.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        detectedPeople.forEach { person ->
                            val id = person.detection.id
                            val ambiguous = id in ambiguousPeople
                            val selected = id in selectedPeople
                            Box(Modifier
                                .background(
                                    if (selected) Color(0xFFFFC21C) else Color(0xFF282828),
                                    RoundedCornerShape(9.dp))
                                .clickable(enabled = !busy && !ambiguous) {
                                    if (!selectedPeople.remove(id)) selectedPeople.add(id)
                                    strokes.clear()
                                    redoStrokes.clear()
                                    maskActions.clear()
                                }
                                .padding(horizontal = 10.dp, vertical = 7.dp)) {
                                Text("Человек ${id + 1}" + if (ambiguous) " · ?" else "",
                                    fontSize = 12.sp,
                                    color = if (selected) Color.Black
                                        else if (ambiguous) Color.Gray else Color.White)
                            }
                        }
                        Box(Modifier.clickable(enabled = !busy) { findPeople() }
                            .padding(horizontal = 8.dp, vertical = 7.dp)) {
                            Text("Повторить поиск", fontSize = 12.sp, color = Color(0xFFFFC21C))
                        }
                    }
                }
                if (selectedPeople.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().height(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        androidx.compose.material3.FilterChip(
                            selected = refiningPeople,
                            onClick = {
                                refiningPeople = !refiningPeople
                                eraserMode = false
                                moveMode = false
                            },
                            enabled = !busy,
                            label = { Text("Уточнить маску", fontSize = 11.sp) },
                        )
                        if (refiningPeople) {
                            RemovalToolButton(R.drawable.ic_ai_eraser_brush_tool,
                                "Добавить к маске", !eraserMode) {
                                eraserMode = false
                                moveMode = false
                            }
                            RemovalToolButton(R.drawable.ic_ai_eraser_erase_tool,
                                "Вычесть из маски", eraserMode) {
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
                        }
                    }
                }
            }
        }
        AiEraserModeStrip(
            selected = selectedMode,
            enabled = !busy,
            onUnavailable = { mode ->
                // Informative tap, not an activation: no model, inference or mask changes.
                featureNotice = mode.unavailableExplanation
            },
            onMode = { mode ->
                featureNotice = null
                selectedMode = mode
                refiningPeople = false
                if (mode == AiEraserMode.OBJECTS) autoMode = mobileReady
                textMode = mode == AiEraserMode.TEXT
                moveMode = false
                eraserMode = false
                strokes.clear()
                redoStrokes.clear()
                maskActions.clear()
                inProgress = emptyList()
                autoTrace = emptyList()
                objectTrace = emptyList()
                selectedGlyphs.clear()
                redoGlyphs.clear()
                glyphs = emptyList()
                detectedPeople = emptyList()
                selectedPeople.clear()
                ambiguousPeople = emptySet()
                message = null
            },
        )
        if (downloading) {
            // Real byte-derived download progress only. Never show the
            // indeterminate purple M3 loading bar during ONNX inference.
            LinearProgressIndicator(
                progress = { downloadProgress.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = Color(0xFFFFC21C),
                trackColor = Color(0xFF383838),
            )
        }
        AiEraserFooter(
            canApply = !busy && index > 0,
            onCancel = onClose,
            onApply = ::acceptResult,
        )
    }
    SnackbarHost(
        hostState = noticeHost,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 68.dp),
    ) { data ->
        Snackbar(data, containerColor = Color(0xFF292929), contentColor = Color.White)
    }
}
}

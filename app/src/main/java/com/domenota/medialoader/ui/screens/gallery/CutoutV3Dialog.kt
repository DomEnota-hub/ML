package com.domenota.medialoader.ui.screens.gallery

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.domenota.medialoader.core.database.DownloadEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Approved MediaLoader cutout editor HTML v3, 06 Nov 2026.
 * UI is intentionally independent of the other photo tools and Gallery v10.
 * Layout and colors mirror MediaLoader_Вырезка_Фото_Макет_v3_Палитра_фоторедактора.html.
 */
private val v3Surface = Color(0xFF252525)
private val v3Bottom = Color(0xFF2C2C2C)
private val v3Workspace = Color(0xFF202020)
private val v3Card = Color(0xFF303030)
private val v3Accent = Color(0xFFFFD34F)
private val v3ActiveCard = Color(0xFF423C29)
private val v3Text = Color(0xFFF6F6F6)
private val v3Muted = Color(0xFFA8A8A8)
private val v3Hairline = Color(0xFF494949)

@Composable
internal fun CutoutV3Dialog(photo: DownloadEntity, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var retry by remember(photo.id) { mutableIntStateOf(0) }
    var busy by remember(photo.id) { mutableStateOf(true) }
    var saving by remember(photo.id) { mutableStateOf(false) }
    var error by remember(photo.id) { mutableStateOf<String?>(null) }
    var source by remember(photo.id) { mutableStateOf<Bitmap?>(null) }
    var cutout by remember(photo.id) { mutableStateOf<GalleryPhotoTools.Cutout?>(null) }
    var selectedTool by remember(photo.id) { mutableStateOf("erase") }
    var originalVisible by remember(photo.id) { mutableStateOf(false) }
    var brushRadius by remember(photo.id) { mutableFloatStateOf(32f) }
    var brushPreview by remember(photo.id) { mutableStateOf(false) }
    var zoom by remember(photo.id) { mutableFloatStateOf(1f) }
    var zoomReset by remember(photo.id) { mutableIntStateOf(0) }
    var maskRevision by remember(photo.id) { mutableIntStateOf(0) }
    var legacySnapshot by remember(photo.id) { mutableStateOf<Bitmap?>(null) }
    var linePoints by remember(photo.id) { mutableStateOf<List<Offset>>(emptyList()) }
    var lineStops by remember(photo.id) { mutableStateOf<List<Int>>(emptyList()) }
    var lineArea by remember(photo.id) { mutableStateOf(false) }
    var lineRestore by remember(photo.id) { mutableStateOf(false) }
    var attraction by remember(photo.id) { mutableFloatStateOf(65f) }
    var tracer by remember(photo.id) { mutableStateOf<MagneticContourTracer?>(null) }

    val editor = remember(source, cutout) {
        val src = source
        val foreground = cutout?.foreground
        if (src != null && foreground != null &&
            src.width == foreground.width && src.height == foreground.height) {
            CutoutMaskEditor(src, foreground)
        } else null
    }

    DisposableEffect(editor) {
        onDispose { editor?.release() }
    }
    LaunchedEffect(editor, selectedTool == "lines") {
        if (editor != null && selectedTool == "lines" && tracer == null) {
            try {
                val originalMask = cutout?.foreground
                if (originalMask != null)
                    tracer = withContext(Dispatchers.Default) {
                        MagneticContourTracer(editor.sourceReference, originalMask)
                    }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // The editor remains usable with its original brush tools.
                tracer = null
            }
        }
    }
    val latestSource by rememberUpdatedState(source)
    val latestCutout by rememberUpdatedState(cutout)
    val latestLegacy by rememberUpdatedState(legacySnapshot)
    DisposableEffect(photo.id) {
        onDispose {
            latestLegacy?.recycle()
            latestSource?.recycle()
            latestCutout?.foreground?.recycle()
            latestCutout?.subjects?.forEach { if (it !== latestCutout?.foreground) it.recycle() }
        }
    }

    LaunchedEffect(photo.id, retry) {
        busy = true
        error = null
        var decoded: Bitmap? = null
        var computed: GalleryPhotoTools.Cutout? = null
        try {
            if (U2NetPhotoCutout.usesOnnx(Build.VERSION.SDK_INT)) {
                withTimeout(100_000L) { U2NetPhotoCutout.prepare(context) }
            } else {
                withTimeout(100_000L) { GalleryPhotoTools.prepareCutoutModel(context) }
            }
            decoded = GalleryPhotoTools.decode(context,
                Uri.parse(requireNotNull(photo.savedUri) { "Файл отсутствует" }),
                if (U2NetPhotoCutout.usesOnnx(Build.VERSION.SDK_INT)) 1600 else 2560)
            val decodedImage = requireNotNull(decoded)
            computed = withTimeout(120_000L) {
                if (U2NetPhotoCutout.usesOnnx(Build.VERSION.SDK_INT))
                    U2NetPhotoCutout.segment(context, decodedImage)
                else GalleryPhotoTools.cutout(decodedImage)
            }
            source = decoded
            cutout = computed
            decoded = null
            computed = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            error = failure.message ?: "Не удалось выделить объект"
        } finally {
            decoded?.recycle()
            computed?.foreground?.recycle()
            computed?.subjects?.forEach { if (it !== computed?.foreground) it.recycle() }
            busy = false
        }
    }
    LaunchedEffect(brushRadius, brushPreview) {
        if (brushPreview) {
            delay(850)
            brushPreview = false
        }
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun startExport(action: String, closeOnSuccess: Boolean = false) {
        if (saving) return
        if (linePoints.isNotEmpty()) {
            toast("Сначала примени или очисти контур")
            return
        }
        val currentEditor = editor ?: return
        val snapshot = currentEditor.snapshot()
        saving = true
        scope.launch {
            try {
                when (action) {
                    "copy" -> {
                        val uri = GalleryPhotoTools.export(context, snapshot)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "PNG", uri))
                        toast("PNG скопирован")
                    }
                    "save" -> {
                        if (Build.VERSION.SDK_INT >= 29) {
                            GalleryPhotoTools.save(context, snapshot)
                            toast("Копия сохранена в Pictures/MediaLoader")
                            if (closeOnSuccess) onClose()
                        } else {
                            legacySnapshot?.recycle()
                            legacySnapshot = snapshot.copy(Bitmap.Config.ARGB_8888, false)
                            // SAF document chooser is launched from the button below.
                            toast("Выберите место сохранения")
                        }
                    }
                    "share" -> {
                        val uri = GalleryPhotoTools.export(context, snapshot)
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newUri(context.contentResolver, "PNG", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Поделиться PNG"))
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { toast(failure.message ?: "Ошибка экспорта PNG") }
            finally { snapshot.recycle(); saving = false }
        }
    }

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val captured = legacySnapshot
        legacySnapshot = null
        if (uri != null && captured != null) {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(uri)?.use {
                        check(captured.compress(Bitmap.CompressFormat.PNG, 100, it))
                    } ?: throw IllegalStateException("Не удалось открыть документ")
                    toast("Копия PNG сохранена")
                } catch (failure: Exception) { toast(failure.message ?: "Не удалось сохранить PNG") }
                finally { captured.recycle() }
            }
        } else captured?.recycle()
    }

    fun undoContourPoint() {
        if (lineStops.isNotEmpty()) {
            val previousEnd = lineStops.getOrNull(lineStops.lastIndex - 1) ?: 0
            linePoints = linePoints.take(previousEnd)
            lineStops = lineStops.dropLast(1)
        }
    }

    fun clearContour() {
        linePoints = emptyList()
        lineStops = emptyList()
    }

    fun applyContour() {
        val working = editor ?: return
        if (linePoints.size < if (lineArea) 3 else 2) {
            toast(if (lineArea) "Обведи замкнутую область" else "Добавь хотя бы два узла")
            return
        }
        val applied = if (lineArea) working.applyPolygon(linePoints, lineRestore)
            else working.applyPolyline(linePoints, 3f, lineRestore)
        if (applied) {
            maskRevision++
            clearContour()
        } else toast("Контур не изменил маску")
    }

    BackHandler(enabled = true, onBack = onClose)
    Dialog(onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(
            Modifier.fillMaxSize()
                .background(v3Surface)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(60.dp)
                    .background(Brush.verticalGradient(listOf(Color(0xFF2D2D2D), Color(0xFF272727))))
                    .padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                V3HeaderButton("Назад", enabled = !saving, onClick = onClose) {
                    Icon(Icons.Rounded.ArrowBack, null, Modifier.size(24.dp), tint = v3Text)
                }
                Column(Modifier.weight(1f).padding(start = 3.dp)) {
                    Text("Выделить объект", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        color = v3Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Ручная коррекция", fontSize = 10.sp, color = v3Muted)
                }
                V3HeaderButton("Отменить",
                    enabled = (editor?.canUndo == true || lineStops.isNotEmpty()) && !saving,
                    onClick = {
                        if (selectedTool == "lines" && lineStops.isNotEmpty()) undoContourPoint()
                        else if (editor?.undo() == true) maskRevision++
                    }) {
                    Icon(Icons.Rounded.Undo, null, Modifier.size(24.dp),
                        tint = if (editor?.canUndo == true || lineStops.isNotEmpty()) v3Text
                            else Color(0xFF797979))
                }
                V3HeaderButton("Повторить", enabled = editor?.canRedo == true && !saving,
                    onClick = { if (editor?.redo() == true) maskRevision++ }) {
                    Icon(Icons.Rounded.Redo, null, Modifier.size(24.dp),
                        tint = if (editor?.canRedo == true) v3Text else Color(0xFF797979))
                }
                V3HeaderButton("Готово", enabled = editor != null && !saving,
                    active = true, onClick = {
                        if (linePoints.isNotEmpty()) {
                            toast("Сначала примени или очисти контур")
                        } else if (Build.VERSION.SDK_INT >= 29) startExport("save", closeOnSuccess = true)
                        else {
                            legacySnapshot?.recycle()
                            legacySnapshot = editor?.snapshot()
                            createDocument.launch("Photo-${System.currentTimeMillis()}.png")
                        }
                    }) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(24.dp), tint = v3Accent)
                }
            }

            Box(Modifier.fillMaxWidth().weight(1f).background(v3Workspace)) {
                if (busy) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(color = v3Accent, strokeWidth = 3.dp)
                        Text("Выделяем объект на устройстве…", fontSize = 12.sp, color = v3Text)
                    }
                } else if (error != null) {
                    Column(Modifier.align(Alignment.Center).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(error ?: "", fontSize = 13.sp, color = v3Text)
                        V3OutlinedAction("Повторить", enabled = true, onClick = { retry++ },
                            modifier = Modifier.width(152.dp))
                    }
                } else if (editor != null) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val availableWidthDp = maxWidth.value
                        val availableHeightDp = maxHeight.value
                        val imageModifier = Modifier.fillMaxSize()
                            .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 42.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .border(1.dp, Color(0x555D5D5D), RoundedCornerShape(11.dp))
                        Box(imageModifier) {
                            CutoutChecker(Modifier.fillMaxSize())
                            CutoutRetouchCanvas(
                                editor = editor,
                                restore = if (selectedTool == "lines") lineRestore else selectedTool == "restore",
                                panMode = selectedTool == "pan",
                                showOriginal = originalVisible,
                                radius = if (selectedTool == "lines") 2.5f else brushRadius,
                                resetZoomRevision = zoomReset,
                                onZoomChanged = { zoom = it },
                                onStrokeDone = { maskRevision++ },
                                magnetic = selectedTool == "lines",
                                tracer = tracer,
                                attraction = attraction,
                                contourArea = lineArea,
                                draft = linePoints,
                                draftStops = lineStops,
                                onDraftChanged = { linePoints = it },
                                onSegmentFinished = { previous ->
                                    if (linePoints.size > previous) {
                                        lineStops = lineStops + linePoints.size
                                    }
                                },
                                refreshToken = maskRevision,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Row(
                                Modifier.align(Alignment.TopStart).padding(11.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xE6292929))
                                    .border(1.dp, Color(0x55646464), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 9.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(v3Accent))
                                Text(if (originalVisible) "Оригинал" else when (selectedTool) {
                                    "restore" -> "Вернуть"
                                    "pan" -> "Двигать"
                                    "lines" -> if (tracer == null) "Ищем границы…" else "Линии"
                                    else -> "Ластик"
                                }, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = v3Text)
                            }
                            if (brushPreview && selectedTool != "pan" && selectedTool != "lines" && !originalVisible) {
                                val base = min(availableWidthDp / editor.width,
                                    availableHeightDp / editor.height)
                                val diameter = (brushRadius * 2 * base * zoom).coerceIn(16f, 170f).dp
                                Column(Modifier.align(Alignment.Center),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(Modifier.size(diameter)
                                        .border(2.dp, v3Accent, RoundedCornerShape(1000.dp))
                                        .background(Color(0x29FFD34F), RoundedCornerShape(1000.dp)))
                                    Text("${if (selectedTool == "restore") "Вернуть" else "Ластик"} · ${brushRadius.roundToInt()} px",
                                        color = v3Text, fontSize = 12.sp,
                                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                            .background(Color(0xE8252525))
                                            .border(1.dp, Color(0xFF64605A), RoundedCornerShape(10.dp))
                                            .padding(horizontal = 12.dp, vertical = 7.dp))
                                }
                            }
                        }
                        Box(
                            Modifier.align(Alignment.BottomStart).padding(start = 11.dp, bottom = 9.dp)
                                .height(26.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(v3Card)
                                .border(1.dp, Color(0xFF505050), RoundedCornerShape(9.dp))
                                .clickable { zoomReset++ }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Rounded.CenterFocusStrong, null, Modifier.size(14.dp),
                                    tint = Color(0xFFDEDEDE))
                                Text("${(zoom * 100f).roundToInt()}%",
                                    color = Color(0xFFDEDEDE), fontSize = 11.sp)
                            }
                        }
                        // No gesture help caption. The approved layout stays uncluttered.
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(v3Bottom, Color(0xFF262626))))
                    .border(width = 1.dp, color = Color(0xFF424242))
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    V3Tool("Ластик", selectedTool == "erase", enabled = editor != null && !saving,
                        icon = { tint -> Icon(Icons.Rounded.Backspace, null, Modifier.size(21.dp), tint = tint) },
                        onClick = { selectedTool = "erase" }, modifier = Modifier.weight(1f))
                    V3Tool("Вернуть", selectedTool == "restore", enabled = editor != null && !saving,
                        icon = { tint -> Icon(Icons.Rounded.AddCircleOutline, null, Modifier.size(21.dp), tint = tint) },
                        onClick = { selectedTool = "restore" }, modifier = Modifier.weight(1f))
                    V3Tool("Линии", selectedTool == "lines", enabled = editor != null && !saving,
                        icon = { tint -> Icon(Icons.Rounded.Timeline, null, Modifier.size(21.dp), tint = tint) },
                        onClick = { selectedTool = "lines" }, modifier = Modifier.weight(1f))
                    // Tap to show the source photograph at full opacity; tap again
                    // to compare against the current cutout at the same zoom/pan.
                    // Avoid conflicting pointerInput and clickable gesture handlers.
                    V3Tool("Оригинал", originalVisible, enabled = editor != null && !saving,
                        icon = { tint -> Icon(Icons.Rounded.Visibility, null, Modifier.size(21.dp), tint = tint) },
                        onClick = { originalVisible = !originalVisible },
                        modifier = Modifier.weight(1f))
                    V3Tool("Двигать", selectedTool == "pan", enabled = editor != null && !saving,
                        icon = { tint -> Icon(Icons.Rounded.PanTool, null, Modifier.size(21.dp), tint = tint) },
                        onClick = { selectedTool = "pan" }, modifier = Modifier.weight(1f))
                }

                if (selectedTool == "lines") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        V3LineOption("По краю", !lineArea, enabled = !saving,
                            onClick = { lineArea = false }, modifier = Modifier.weight(1f))
                        V3LineOption("Область", lineArea, enabled = !saving,
                            onClick = { lineArea = true }, modifier = Modifier.weight(1f))
                        V3LineOption("Удалить", !lineRestore, enabled = !saving,
                            onClick = { lineRestore = false }, modifier = Modifier.weight(1f))
                        V3LineOption("Вернуть", lineRestore, enabled = !saving,
                            onClick = { lineRestore = true }, modifier = Modifier.weight(1f))
                    }
                    Row(
                        Modifier.fillMaxWidth().height(43.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(v3Card)
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Text("Притяжение", color = v3Text, fontSize = 11.sp)
                        // Material3's default vertical-bar thumb violates our approved
                        // circular-slider style. Use the same circle as the brush slider.
                        CutoutBrushSlider(
                            value = attraction,
                            onValueChange = { attraction = it },
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${attraction.roundToInt()}%", color = v3Text, fontSize = 11.sp)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        V3OutlinedAction("Точка назад", enabled = lineStops.isNotEmpty() && !saving,
                            onClick = ::undoContourPoint, modifier = Modifier.weight(1f))
                        V3OutlinedAction("Очистить", enabled = linePoints.isNotEmpty() && !saving,
                            onClick = ::clearContour, modifier = Modifier.weight(.8f))
                        V3OutlinedAction("Применить",
                            enabled = tracer != null && !saving &&
                                linePoints.size >= if (lineArea) 3 else 2,
                            onClick = ::applyContour, modifier = Modifier.weight(1f),
                            icon = { Icon(Icons.Rounded.Check, null, Modifier.size(15.dp), tint = v3Accent) })
                    }
                } else if (selectedTool != "pan") {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(v3Card)
                            .border(1.dp, Color(0xFF4C4C4C), RoundedCornerShape(11.dp))
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Размер кисти", color = Color(0xFFDBDBDB), fontSize = 11.sp,
                            fontWeight = FontWeight.Medium, maxLines = 1)
                        CutoutBrushSlider(value = brushRadius, onValueChange = {
                            brushRadius = it
                            brushPreview = true
                        }, modifier = Modifier.weight(1f))
                        Text("${brushRadius.roundToInt()}", color = Color(0xFFEDEDED), fontSize = 11.sp)
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 11.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    V3OutlinedAction("Копировать", enabled = editor != null && !saving,
                        onClick = { startExport("copy") }, modifier = Modifier.weight(1f),
                        icon = { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(15.dp), tint = v3Text) })
                    V3OutlinedAction("Сохранить", enabled = editor != null && !saving,
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 29) startExport("save")
                            else {
                                legacySnapshot?.recycle()
                                legacySnapshot = editor?.snapshot()
                                createDocument.launch("Photo-${System.currentTimeMillis()}.png")
                            }
                        }, modifier = Modifier.weight(1f),
                        icon = { Icon(Icons.Rounded.Save, null, Modifier.size(15.dp), tint = v3Text) })
                    V3OutlinedAction("Поделиться", enabled = editor != null && !saving,
                        onClick = { startExport("share") }, modifier = Modifier.weight(1f),
                        icon = { Icon(Icons.Rounded.Share, null, Modifier.size(15.dp), tint = v3Text) })
                }
            }
        }
    }
}

@Composable
private fun V3HeaderButton(label: String, enabled: Boolean,
    active: Boolean = false, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(width = 36.dp, height = 42.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (active) Color(0xFF423A25) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun V3Tool(label: String, active: Boolean, enabled: Boolean,
    icon: @Composable (Color) -> Unit,
    onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(13.dp)
    Column(
        modifier.heightIn(min = 57.dp)
            .clip(shape)
            .background(if (active) v3ActiveCard else Color.Transparent)
            .border(1.dp, if (active) Color(0xFF806C3A) else Color.Transparent, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        icon(if (active) Color(0xFFFFE284) else Color(0xFFBFBFBF))
        Text(label, color = if (active) Color(0xFFFFF0BF) else v3Text,
            fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun V3LineOption(label: String, active: Boolean, enabled: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.height(37.dp).clip(shape)
            .background(if (active) v3ActiveCard else v3Card)
            .border(1.dp, if (active) v3Accent else v3Hairline, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 10.sp, color = v3Text, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun V3OutlinedAction(label: String, enabled: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null) {
    val shape = RoundedCornerShape(13.dp)
    Row(
        modifier.height(43.dp)
            .clip(shape)
            .background(Color(0x05FFFFFF))
            .border(1.3.dp, Color(0xFFBCBCBC), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) icon()
        Text(label, color = v3Text, fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Clip)
    }
}

/** 15dp checker squares: small enough for fine edges without obscuring the subject. */
@Composable
private fun CutoutChecker(modifier: Modifier) {
    Canvas(modifier) {
        val tile = 15.dp.toPx()
        val cols = (size.width / tile).toInt() + 1
        val rows = (size.height / tile).toInt() + 1
        for (y in 0..rows) for (x in 0..cols) {
            drawRect(if ((x + y) % 2 == 0) Color(0xFF373737) else Color(0xFF464646),
                topLeft = Offset(x * tile, y * tile),
                size = androidx.compose.ui.geometry.Size(tile, tile))
        }
    }
}

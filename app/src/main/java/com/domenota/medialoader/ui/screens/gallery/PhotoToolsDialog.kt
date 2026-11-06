package com.domenota.medialoader.ui.screens.gallery

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.editor.LocalTextOcr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Tools always export a separate PNG. The original savedUri is opened for reading only. */
@Composable
internal fun PhotoToolsDialog(photo: DownloadEntity, mode: String, onDismiss: () -> Unit) {
    // Cutout is routed to the approved HTML v3 layout. OCR and watermark remain untouched.
    if (mode == "cutout") {
        CutoutV3Dialog(photo, onDismiss)
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember(photo.id, mode) { mutableStateOf<Bitmap?>(null) }
    var cutout by remember(photo.id, mode) { mutableStateOf<GalleryPhotoTools.Cutout?>(null) }
    var watermarkImage by remember(photo.id, mode) { mutableStateOf<Bitmap?>(null) }
    var text by remember(photo.id, mode) { mutableStateOf("") }
    var watermark by remember(photo.id) { mutableStateOf("") }
    var selectedSubject by remember(photo.id) { mutableIntStateOf(-1) }
    var loading by remember(photo.id, mode) { mutableStateOf(true) }
    var preparingModel by remember(photo.id, mode) { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var error by remember(photo.id, mode) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var brushRestore by remember(photo.id, mode) { mutableStateOf(false) }
    var brushRadius by remember(photo.id, mode) { mutableFloatStateOf(32f) }
    var editorZoom by remember(photo.id, mode) { mutableFloatStateOf(1f) }
    var resetZoomRevision by remember(photo.id, mode) { mutableIntStateOf(0) }
    var maskRevision by remember(photo.id, mode) { mutableIntStateOf(0) }
    val selectedMask = cutout?.let { item -> item.subjects.getOrNull(selectedSubject) ?: item.foreground }
    val currentSource = source
    val maskEditor = remember(currentSource, selectedMask, mode) {
        if (mode == "cutout" && currentSource != null && selectedMask != null &&
            currentSource.width == selectedMask.width && currentSource.height == selectedMask.height)
            CutoutMaskEditor(currentSource, selectedMask) else null
    }
    DisposableEffect(maskEditor) { onDispose { maskEditor?.release() } }
    val result = if (mode == "watermark") watermarkImage else maskEditor?.preview ?: selectedMask
    @Suppress("UNUSED_VARIABLE") val maskChange = maskRevision
    fun notify(message: String) { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    fun perform(action: suspend () -> Unit) {
        scope.launch {
            exporting = true
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Не удалось выполнить действие" }
            finally { exporting = false }
        }
    }
    var documentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bitmap = documentBitmap
        documentBitmap = null
        if (uri != null && bitmap != null) perform {
            withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri)) { "Не удалось открыть файл" }.use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Не удалось сохранить PNG" }
                }
            }
            if (bitmap !== source && bitmap !== watermarkImage && bitmap !== cutout?.foreground)
                bitmap.recycle()
            notify("Копия PNG сохранена")
        }
    }
    LaunchedEffect(photo.id, mode, retry) {
        loading = true
        error = null
        cutout = null
        text = ""
        try {
            // ML Kit 16.0.0-beta1 may SIGSEGV during inference on Android 16.
            // No Java exception handler can catch that native crash. Never invoke it
            // on API 36+: use the existing ONNX CPU runtime instead.
            val onnxCutout = mode == "cutout" && U2NetPhotoCutout.usesOnnx(Build.VERSION.SDK_INT)
            if (mode == "cutout") {
                preparingModel = true
                withTimeout(100_000L) {
                    if (onnxCutout) U2NetPhotoCutout.prepare(context)
                    else GalleryPhotoTools.prepareCutoutModel(context)
                }
                preparingModel = false
            }
            val edge = when {
                onnxCutout -> U2NetPhotoCutout.MAX_IMAGE_EDGE
                mode == "ocr" -> 4000
                else -> 2560
            }
            val image = GalleryPhotoTools.decode(context, Uri.parse(requireNotNull(photo.savedUri)), edge)
            source = image
            when (mode) {
                "ocr" -> text = LocalTextOcr.readPage(context, image).text
                "cutout" -> cutout = withTimeout(120_000) {
                    if (onnxCutout) U2NetPhotoCutout.segment(context, image)
                    else GalleryPhotoTools.cutout(image)
                }
                "watermark" -> Unit
                else -> error = "Неизвестный инструмент"
            }
        } catch (timeout: TimeoutCancellationException) {
            error = if (mode == "cutout" && U2NetPhotoCutout.usesOnnx(Build.VERSION.SDK_INT))
                "Локальная модель не успела завершить обработку. Повторите операцию."
            else "Модель ещё не готова. Проверьте интернет и Google Play сервисы, затем повторите."
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            error = when {
                mode == "cutout" && U2NetPhotoCutout.usesOnnx(Build.VERSION.SDK_INT) ->
                    (failure.message ?: "Не удалось выделить объект") +
                        "\nПри первом использовании необходим интернет для загрузки локальной модели."
                mode == "cutout" ->
                    (failure.message ?: "Не удалось выделить объект") +
                        "\nДля загрузки модели нужны интернет и обновлённые Google Play сервисы."
                else -> failure.message ?: "Не удалось обработать фотографию"
            }
        } finally { preparingModel = false; loading = false }
    }
    LaunchedEffect(source, watermark) {
        watermarkImage = null
        val image = source
        if (mode == "watermark" && image != null && watermark.isNotBlank()) {
            delay(250)
            try { watermarkImage = GalleryPhotoTools.watermark(image, watermark) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Не удалось добавить водяной знак" }
        }
    }
    val title = when (mode) { "ocr" -> "Текст с фото"; "cutout" -> "Выделить объект"; else -> "Водяной знак" }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.95f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text(if (loading) "Отмена" else "Закрыть") }
                }
                if (mode == "ocr" && !loading && error == null) {
                    SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Text(text.ifBlank { "Текст не найден. Попробуйте более чёткую фотографию." })
                    }
                    Text("Русский и английский · распознавание на устройстве", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = text.isNotBlank(), onClick = {
                            clipboard(context).setPrimaryClip(ClipData.newPlainText("Текст с фото", text)); notify("Текст скопирован")
                        }) { Text("Копировать") }
                        TextButton(enabled = text.isNotBlank(), onClick = {
                            perform { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                            }, "Поделиться текстом")) }
                        }) { Text("Поделиться") }
                    }
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF252525)), contentAlignment = Alignment.Center) {
                        val preview = if (mode == "watermark") watermarkImage ?: source else result ?: source
                        if (result != null) TransparencyGrid(Modifier.fillMaxSize())
                        if (mode == "cutout" && maskEditor != null && !loading) {
                            CutoutRetouchCanvas(maskEditor, brushRestore, brushRadius,
                                resetZoomRevision = resetZoomRevision,
                                onZoomChanged = { editorZoom = it },
                                onStrokeDone = { maskRevision++ }, modifier = Modifier.fillMaxSize())
                        } else {
                            preview?.let { Image(it.asImageBitmap(), "Предпросмотр", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                        }
                        if (loading) {
                            if (mode == "cutout") ScanningDots(Modifier.fillMaxSize())
                            else CircularProgressIndicator()
                        }
                    }
                    if (loading) Text(if (mode == "cutout") {
                        if (preparingModel && U2NetPhotoCutout.usesOnnx(Build.VERSION.SDK_INT))
                            "Проверяем локальную модель. При первом запуске загрузка ~4,6 МБ, нужен интернет…"
                        else if (preparingModel)
                            "Проверяем модуль Google Play. При необходимости загружаем модель, нужен интернет…"
                        else "Выделяем объекты на устройстве…"
                    } else "Распознаём текст на устройстве…", style = MaterialTheme.typography.bodySmall)
                    if (mode == "cutout" && maskEditor != null && !loading) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(selected = !brushRestore, onClick = { brushRestore = false },
                                label = { Text("Ластик") })
                            FilterChip(selected = brushRestore, onClick = { brushRestore = true },
                                label = { Text("Вернуть") })
                            TextButton(onClick = {
                                if (maskEditor.undo()) maskRevision++
                            }, enabled = maskEditor.canUndo) { Text("Отменить") }
                            TextButton(onClick = {
                                if (maskEditor.redo()) maskRevision++
                            }, enabled = maskEditor.canRedo) { Text("Повторить") }
                            TextButton(onClick = {
                                maskEditor.reset()
                                maskRevision++
                            }) { Text("Сброс") }
                            TextButton(
                                onClick = { resetZoomRevision++ },
                                enabled = editorZoom > 1.001f,
                            ) { Text(if (editorZoom > 1.001f) "${(editorZoom * 100).toInt()}% · 1×" else "1×") }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Размер", style = MaterialTheme.typography.labelMedium)
                            CutoutBrushSlider(value = brushRadius,
                                onValueChange = { brushRadius = it }, modifier = Modifier.weight(1f))
                            Text(brushRadius.toInt().toString(), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (mode == "watermark") {
                        OutlinedTextField(value = watermark, onValueChange = { watermark = it.take(160) },
                            label = { Text("Текст водяного знака") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    if (mode == "cutout" && cutout != null) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = selectedSubject == -1, onClick = { selectedSubject = -1 }, label = { Text("Все объекты") })
                            cutout?.subjects?.forEachIndexed { index, _ ->
                                FilterChip(selected = selectedSubject == index, onClick = { selectedSubject = index }, label = { Text("Объект ${index + 1}") })
                            }
                        }
                    }
                    if (mode != "ocr" && !loading) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = result != null && !exporting, modifier = Modifier.weight(1f).height(44.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = .6f)),
                                contentPadding = PaddingValues(horizontal = 3.dp), onClick = { result?.let { image ->
                                val snapshot = if (mode == "cutout") maskEditor?.snapshot() else null
                                perform {
                                    try {
                                        val uri = GalleryPhotoTools.export(context, snapshot ?: image)
                                        clipboard(context).setPrimaryClip(ClipData.newUri(context.contentResolver, "PNG", uri))
                                        notify("PNG скопирован")
                                    } finally { snapshot?.recycle() }
                                }
                            } }) { Text("Копировать", color = Color.White, fontSize = 12.sp, maxLines = 1) }
                            OutlinedButton(enabled = result != null && !exporting, modifier = Modifier.weight(1f).height(44.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = .6f)),
                                contentPadding = PaddingValues(horizontal = 3.dp), onClick = { result?.let { image ->
                                val snapshot = if (mode == "cutout") maskEditor?.snapshot() else null
                                if (Build.VERSION.SDK_INT >= 29) perform {
                                    try {
                                        GalleryPhotoTools.save(context, snapshot ?: image)
                                        notify("Копия сохранена в Pictures/MediaLoader")
                                    } finally { snapshot?.recycle() }
                                }
                                else {
                                    documentBitmap = snapshot ?: image
                                    saveDocument.launch("Photo-${System.currentTimeMillis()}.png")
                                }
                            } }) { Text("Сохранить", color = Color.White, fontSize = 12.sp, maxLines = 1) }
                            OutlinedButton(enabled = result != null && !exporting, modifier = Modifier.weight(1f).height(44.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = .6f)),
                                contentPadding = PaddingValues(horizontal = 3.dp), onClick = { result?.let { image ->
                                val snapshot = if (mode == "cutout") maskEditor?.snapshot() else null
                                perform {
                                    try {
                                        val uri = GalleryPhotoTools.export(context, snapshot ?: image)
                                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                            type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri)
                                            clipData = ClipData.newUri(context.contentResolver, "PNG", uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }, "Поделиться PNG"))
                                    } finally { snapshot?.recycle() }
                                }
                            } }) { Text("Поделиться", color = Color.White, fontSize = 12.sp, maxLines = 1) }
                        }
                    }
                }
                error?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()))
                    TextButton(onClick = { retry++ }, enabled = !loading && !exporting) { Text("Повторить") }
                }
                if (exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

private fun clipboard(context: Context): ClipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

@Composable
private fun TransparencyGrid(modifier: Modifier) {
    Canvas(modifier) {
        val step = 18.dp.toPx()
        for (row in 0..(size.height / step).toInt()) for (column in 0..(size.width / step).toInt()) {
            drawRect(if ((row + column) % 2 == 0) Color(0xFFCCCCCC) else Color(0xFFEEEEEE), Offset(column * step, row * step), Size(step, step))
        }
    }
}

@Composable
private fun ScanningDots(modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "subject scan")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2100, easing = LinearEasing), RepeatMode.Restart), label = "scan dots")
    Canvas(modifier) {
        drawRect(Color.Black.copy(alpha = 0.16f))
        for (index in 0 until 65) {
            val x = ((index * 37 % 101) / 100f) * size.width
            val y = ((index * 61 % 103) / 102f) * size.height
            val offset = (phase + index * 0.137f) % 1f
            val alpha = (1f - kotlin.math.abs(offset * 2f - 1f)).coerceIn(0f, 1f)
            drawCircle(Color.White.copy(alpha = alpha), radius = (1.5f + alpha * 1.5f).dp.toPx(), center = Offset(x, y))
        }
    }
}

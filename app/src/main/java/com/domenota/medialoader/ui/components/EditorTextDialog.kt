package com.domenota.medialoader.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import com.domenota.medialoader.ui.components.AppSlider as Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import com.domenota.medialoader.data.editor.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** Edits a draft. Positions are normalized to the final output; video previews are chosen frames. */
@Composable
internal fun EditorTextDialog(
    initial: List<TextLayer>,
    durationMs: Long? = null,
    initialPositionMs: Long = 0L,
    videoSource: TextEditorVideoSource? = null,
    loadPreview: suspend (Long) -> Bitmap,
    onApply: (List<TextLayer>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var layers by remember { mutableStateOf(initial) }
    var selectedId by remember { mutableStateOf(initial.firstOrNull()?.id) }
    var position by remember { mutableStateOf(initialPositionMs.coerceIn(0L, (durationMs ?: 0L).coerceAtLeast(0L))) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var previewPosition by remember { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    var selectSample by remember { mutableStateOf(false) }
    var pickColor by remember { mutableStateOf(false) }
    var inspectedColor by remember { mutableStateOf<Int?>(null) }
    var sampleBounds by remember { mutableStateOf<Rect?>(null) }
    var sample by remember { mutableStateOf<Bitmap?>(null) }
    var sampleText by remember { mutableStateOf("") }
    var matching by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<EditorFont>>(emptyList()) }
    var fontsReady by remember { mutableStateOf(false) }
    val videoPlayer = rememberTextEditorVideoPlayer(videoSource)
    var playing by remember { mutableStateOf(false) }
    var intervalEnd by remember { mutableStateOf<Long?>(null) }
    var timingTab by remember { mutableStateOf(false) }
    var expandedControls by remember { mutableStateOf(false) }
    var previewZoom by remember { mutableStateOf(PreviewZoom()) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    fun seek(time: Long) {
        pickColor = false; inspectedColor = null
        videoPlayer?.pause()
        playing = false
        intervalEnd = null
        position = TextTiming.seekPosition(time, durationMs ?: 1L)
        videoPlayer?.seekTo(position)
    }
    fun zoomPreview(next: PreviewZoom) {
        if (videoPlayer?.playWhenReady == true) seek(videoPlayer.currentPosition)
        previewZoom = next
    }
    DisposableEffect(videoPlayer) {
        videoPlayer?.seekTo(position)
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                videoPlayer?.pause()
                playing = false
                message = "Не удалось воспроизвести видео. Выбор по кадрам остаётся доступен."
            }
        }
        videoPlayer?.addListener(listener)
        onDispose { videoPlayer?.removeListener(listener) }
    }
    LaunchedEffect(videoPlayer) {
        val player = videoPlayer ?: return@LaunchedEffect
        while (true) {
            playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
            if (playing) {
                position = player.currentPosition.coerceIn(0L, durationMs ?: Long.MAX_VALUE)
                intervalEnd?.let { end -> if (position >= end) seek(end) }
            } else if (player.playbackState == Player.STATE_ENDED) {
                position = durationMs ?: player.currentPosition
            }
            delay(50)
        }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { EditorFonts.all.forEach { EditorFonts.face(context, it.id) } }
        fontsReady = true
    }
    val selected = layers.firstOrNull { it.id == selectedId }
    val currentSelected by rememberUpdatedState(selected)
    LaunchedEffect(selectedId) { pickColor = false; inspectedColor = null }
    fun update(layer: TextLayer) { layers = layers.map { if (it.id == layer.id) layer else it } }
    fun close() { if (layers != initial) confirmExit = true else onDismiss() }
    suspend fun matchSample(source: Bitmap, text: String, targetId: String?) {
        val found = FontSampleMatcher.match(context, source, text)
        suggestions = found
        found.firstOrNull()?.let { font ->
            currentSelected?.takeIf { it.id == targetId }?.let { update(it.copy(fontId = font.id)) }
        }
        message = if (found.isEmpty()) "Уверенно подобрать шрифт не удалось. Проверь текст образца или выбери вручную."
            else "Предложены похожие шрифты из библиотеки. Точное совпадение не гарантируется."
    }

    LaunchedEffect(position, playing) {
        if (playing) { loading = false; return@LaunchedEffect }
        loading = true
        try {
            delay(150)
            preview = loadPreview(position)
            previewPosition = position
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "Не удалось прочитать изображение или кадр" }
        finally { loading = false }
    }
    LaunchedEffect(sample) {
        val image = sample ?: return@LaunchedEffect
        suggestions = emptyList()
        sampleText = ""
        matching = true
        message = "Читаем русский или английский текст…"
        val targetId = selectedId
        try {
            val result = LocalTextOcr.readLine(context, image)
            sampleText = result.text
            if (result.text.isNotBlank() && result.confidence >= 35) {
                message = "Сравниваем шрифты…"
                matchSample(image, result.text, targetId)
            } else message = "Не удалось уверенно прочитать надпись. Введи текст образца и нажми «Подобрать похожие»."
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "Распознавание не удалось. Введи текст образца вручную." }
        finally { matching = false }
    }

    EditorWindow(onDismissRequest = ::close) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                val compact = maxHeight < 560.dp
                val minimal = maxHeight < 280.dp
                // Keep the image large by default; expand advanced options only on demand.
                val previewHeight = when {
                    minimal -> (maxHeight * 0.27f).coerceAtLeast(48.dp)
                    expandedControls -> (maxHeight * 0.37f).coerceIn(120.dp, 330.dp)
                    else -> (maxHeight * 0.58f).coerceIn(170.dp, 460.dp)
                }
                LaunchedEffect(minimal) { if (minimal) {
                    videoPlayer?.pause(); playing = false; pickColor = false; inspectedColor = null
                } }
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Текст", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = ::close) { Text("Отмена") }
                        OutlinedButton(onClick = {
                            onApply(layers.filter { it.text.isNotBlank() }.mapNotNull {
                                if (durationMs == null) it else it.within(durationMs)
                            })
                        }, enabled = !matching) { Text("Применить") }
                    }
                    val image = preview
                    Box(Modifier.fillMaxWidth().height(previewHeight).background(Color.Black).clipToBounds()
                        .onSizeChanged { if (previewSize != it) { previewSize = it; previewZoom = PreviewZoom() } }
                        .previewZoomGestures({ previewZoom }, { preview?.let { it.width to it.height } }, ::zoomPreview)) {
                        Box(Modifier.fillMaxSize().graphicsLayer {
                            scaleX = previewZoom.scale; scaleY = previewZoom.scale
                            translationX = previewZoom.x; translationY = previewZoom.y
                        }) {
                        if (videoPlayer != null && previewZoom.scale == 1f) TextEditorVideoSurface(videoPlayer, Modifier.fillMaxSize())
                        if (image != null) {
                            if (videoPlayer == null || videoPlayer.playerError != null || selectSample || pickColor || previewZoom.scale > 1f) Image(image.asImageBitmap(), contentDescription = "Предпросмотр текста",
                                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            Canvas(Modifier.fillMaxSize().pointerInput(image, selectedId, selectSample, position, playing, loading) {
                                if (!selectSample || playing || loading || previewPosition != position) return@pointerInput
                                val scale = minOf(size.width.toFloat() / image.width, size.height.toFloat() / image.height)
                                val width = image.width * scale; val height = image.height * scale
                                val left = (size.width - width) / 2f; val top = (size.height - height) / 2f
                                fun point(offset: Offset) = Offset(((offset.x - left) / width).coerceIn(0f, 1f),
                                    ((offset.y - top) / height).coerceIn(0f, 1f))
                                var origin = Offset.Zero
                                detectDragGestures(
                                    onDragStart = { origin = point(it); if (selectSample) sampleBounds = Rect(origin, origin) },
                                    onDrag = { change, _ ->
                                        if (selectSample) {
                                            val end = point(change.position)
                                            sampleBounds = Rect(minOf(origin.x, end.x), minOf(origin.y, end.y),
                                                maxOf(origin.x, end.x), maxOf(origin.y, end.y))
                                        }
                                        change.consume()
                                    },
                                    onDragEnd = {
                                        if (selectSample) sampleBounds?.let { bounds ->
                                            val x = (bounds.left * image.width).toInt().coerceIn(0, image.width - 1)
                                            val y = (bounds.top * image.height).toInt().coerceIn(0, image.height - 1)
                                            val w = (bounds.width * image.width).toInt().coerceAtMost(image.width - x)
                                            val h = (bounds.height * image.height).toInt().coerceAtMost(image.height - y)
                                            if (w >= 6 && h >= 6) {
                                                sample = Bitmap.createBitmap(image, x, y, w, h)
                                                selectSample = false
                                            }
                                        }
                                    },
                                )
                            }) {
                                val scale = minOf(size.width / image.width, size.height / image.height)
                                val left = (size.width - image.width * scale) / 2f
                                val top = (size.height - image.height * scale) / 2f
                                val native = drawContext.canvas.nativeCanvas
                                val save = native.save()
                                native.translate(left, top); native.scale(scale, scale)
                                native.clipRect(0, 0, image.width, image.height)
                                if (!pickColor) TextLayerRenderer.draw(context, native, image.width, image.height, layers,
                                    positionMs = position, includeAll = durationMs == null)
                                native.restoreToCount(save)
                                if (selectSample) sampleBounds?.let { bounds ->
                                    drawRect(Color.Yellow, Offset(left + bounds.left * image.width * scale,
                                        top + bounds.top * image.height * scale),
                                        androidx.compose.ui.geometry.Size(bounds.width * image.width * scale,
                                            bounds.height * image.height * scale), style = Stroke(2.dp.toPx()))
                                }
                            }
                            selected?.takeIf { !selectSample && !pickColor && !playing && it.text.isNotBlank() &&
                                (durationMs == null || it.visibleAt(position)) }?.let { layer ->
                                TextLayerSelectionOverlay(image.width, image.height, layer, ::update, previewZoom)
                                Text("${layer.rotation.roundToInt()}° · ${(layer.size * 100).roundToInt()}%",
                                    color = Color.White, style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.align(Alignment.TopStart)
                                        .background(Color.Black.copy(alpha = 0.6f)).padding(4.dp))
                            }
                        }
                        }
                        if (image != null && pickColor && !loading && !playing && previewPosition == position) {
                            TextColorPickerOverlay(image, onInspect = { inspectedColor = it }, onPick = { color ->
                                currentSelected?.let { update(it.copy(color = color)) }
                                pickColor = false; inspectedColor = null
                            }, zoom = previewZoom)
                        }
                        if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                    if (!compact) Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedIconButton(onClick = { zoomPreview(previewZoom.transform(1f / 1.5f, 0f, 0f,
                            previewSize.width / 2f, previewSize.height / 2f,
                            previewSize.width.toFloat(), previewSize.height.toFloat(), image?.width ?: 0, image?.height ?: 0)) }, enabled = previewZoom.scale > 1f) {
                            Icon(Icons.Rounded.ZoomOut, contentDescription = "Уменьшить изображение")
                        }
                        OutlinedButton(onClick = { previewZoom = PreviewZoom() }) { Text("${(previewZoom.scale * 100).roundToInt()}%") }
                        OutlinedIconButton(onClick = { zoomPreview(previewZoom.transform(1.5f, 0f, 0f,
                            previewSize.width / 2f, previewSize.height / 2f,
                            previewSize.width.toFloat(), previewSize.height.toFloat(), image?.width ?: 0, image?.height ?: 0)) }, enabled = previewZoom.scale < 6f) {
                            Icon(Icons.Rounded.ZoomIn, contentDescription = "Увеличить изображение")
                        }
                        Text("2 пальца — масштаб и перемещение", style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f))
                    }
                    if (!compact || selectSample || pickColor) Text(when {
                        pickColor && (loading || previewPosition != position) -> "Готовим кадр для пипетки…"
                        pickColor -> inspectedColor?.let { "Цвет ${ImageColorSample.hex(it)} · отпусти, чтобы применить" }
                            ?: "Коснись исходной надписи. Веди палец для точного выбора цвета."
                        selectSample -> "Обведи одну исходную строку текста"
                        else -> "Тяни надпись для перемещения, угловой маркер — для размера и поворота"
                    },
                        style = MaterialTheme.typography.bodySmall)
                    if (durationMs != null && durationMs > 0) {
                        if (!minimal) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (videoPlayer != null) OutlinedIconButton(onClick = {
                                if (videoPlayer.playWhenReady && videoPlayer.playbackState != Player.STATE_ENDED) {
                                    videoPlayer.pause(); playing = false
                                    position = videoPlayer.currentPosition.coerceIn(0L, durationMs)
                                }
                                else {
                                    if (videoPlayer.playbackState == Player.STATE_ENDED || position >= durationMs - 1) seek(0)
                                    intervalEnd = null
                                    selectSample = false; sampleBounds = null
                                    pickColor = false; inspectedColor = null
                                    previewZoom = PreviewZoom()
                                    videoPlayer.play(); playing = true
                                }
                            }, enabled = videoPlayer.playerError == null) {
                                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (playing) "Пауза" else "Смотреть со звуком")
                            }
                            Text(textTime(position) + " / " + textTime(durationMs),
                                style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                            if (!compact) {
                                OutlinedButton(onClick = { seek(position - 100) }) { Text("−0,1 с") }
                                OutlinedButton(onClick = { seek(position + 100) }) { Text("+0,1 с") }
                            }
                        }
                        if (!compact) Slider(value = position.coerceAtMost(durationMs).toFloat(), onValueChange = { seek(it.toLong()) },
                            valueRange = 0f..durationMs.toFloat())
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !timingTab, onClick = { timingTab = false }, label = { Text("Надпись") })
                            FilterChip(selected = timingTab, onClick = {
                                timingTab = true; selectSample = false; sampleBounds = null
                                pickColor = false; inspectedColor = null
                            }, label = { Text("Время появления") })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { expandedControls = !expandedControls }) {
                            Text(if (expandedControls) "Свернуть инструменты" else "Все настройки текста")
                        }
                    }
                    if (!expandedControls) {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                            .padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                layers.forEachIndexed { index, layer ->
                                    FilterChip(selected = layer.id == selectedId,
                                        onClick = { selectedId = layer.id },
                                        label = { Text("Текст ${index + 1}") })
                                }
                                OutlinedButton(onClick = {
                                    val added = TextLayer(startMs = 0L, endMs = durationMs)
                                    layers = layers + added; selectedId = added.id
                                }, enabled = layers.size < 8) { Text("+ Добавить") }
                            }
                            selected?.let { layer ->
                                OutlinedTextField(layer.text,
                                    { update(layer.copy(text = it.take(500))) },
                                    label = { Text("Надпись") },
                                    modifier = Modifier.fillMaxWidth(), maxLines = 3)
                            }
                        }
                    } else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        sample?.let { source ->
                            OutlinedTextField(sampleText, { sampleText = it.take(120); suggestions = emptyList() },
                                label = { Text("Текст на образце — проверь распознавание") }, modifier = Modifier.fillMaxWidth())
                            OutlinedButton(onClick = {
                                matching = true
                                val target = selectedId
                                scope.launch {
                                    try { matchSample(source, sampleText, target) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { message = "Не удалось сравнить шрифты. Выбери вручную." }
                                    finally { matching = false }
                                }
                            }, enabled = !matching && sampleText.isNotBlank()) {
                                Text(if (matching) "Распознаём и сравниваем…" else "Подобрать похожие")
                            }
                        }
                        if (suggestions.isNotEmpty()) Text("Похожие по образцу", style = MaterialTheme.typography.titleSmall)
                        suggestions.forEach { font ->
                            OutlinedButton(onClick = { currentSelected?.let { update(it.copy(fontId = font.id)) } }) {
                                Text(font.label, fontFamily = FontFamily(EditorFonts.face(context, font.id)))
                            }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            layers.forEachIndexed { index, layer ->
                                FilterChip(selected = layer.id == selectedId, onClick = { selectedId = layer.id },
                                    label = { Text("Текст ${index + 1}") })
                            }
                            OutlinedButton(onClick = {
                                val added = TextLayer(startMs = 0L, endMs = durationMs)
                                layers = layers + added; selectedId = added.id
                            }, enabled = layers.size < 8) { Text("+ Добавить") }
                        }
                        selected?.let { layer ->
                            if (durationMs != null && !layer.visibleAt(position)) {
                                OutlinedButton(onClick = { seek(layer.startMs) }) { Text("Показать кадр с этой надписью") }
                            }
                            if (!timingTab || durationMs == null) {
                                OutlinedTextField(layer.text, { update(layer.copy(text = it.take(500))) },
                                    label = { Text("Надпись") }, modifier = Modifier.fillMaxWidth(), maxLines = 5)
                                Text("Обводка")
                                Slider(layer.outline, { update(layer.copy(outline = it)) }, valueRange = 0f..0.15f)
                                val colors = listOf(-1, -16777216, 0xFFFF4444.toInt(), 0xFFFFDD33.toInt(),
                                    0xFF44AAFF.toInt(), 0xFF55DD88.toInt(), 0xFF909090.toInt())
                                Row(Modifier.horizontalScroll(rememberScrollState())) {
                                    colors.forEach { color ->
                                        IconButton(onClick = {
                                            pickColor = false; inspectedColor = null
                                            update(layer.copy(color = color))
                                        }) {
                                            Box(Modifier.size(32.dp).border(if (layer.color == color) 3.dp else 1.dp,
                                                if (layer.color == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                                MaterialTheme.shapes.small).padding(4.dp)
                                                .background(Color(color), MaterialTheme.shapes.extraSmall))
                                        }
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = {
                                        if (pickColor) { pickColor = false; inspectedColor = null }
                                        else {
                                            videoPlayer?.let { seek(it.currentPosition) }
                                            selectSample = false; sampleBounds = null
                                            pickColor = true; inspectedColor = null
                                        }
                                    }, enabled = image != null && !minimal, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Rounded.Colorize, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (pickColor) "Отменить пипетку" else "Пипетка")
                                    }
                                    Box(Modifier.size(24.dp).border(1.dp, MaterialTheme.colorScheme.outline,
                                        MaterialTheme.shapes.extraSmall).padding(2.dp)
                                        .background(Color(layer.color), MaterialTheme.shapes.extraSmall))
                                    Text(ImageColorSample.hex(layer.color), style = MaterialTheme.typography.labelMedium)
                                }
                                Row {
                                    OutlinedButton(onClick = { update(layer.copy(outlineColor = android.graphics.Color.BLACK)) }) { Text("Чёрная обводка") }
                                    OutlinedButton(onClick = { update(layer.copy(outlineColor = android.graphics.Color.WHITE)) }) { Text("Белая") }
                                }
                                OutlinedButton(onClick = { videoPlayer?.pause(); playing = false; pickColor = false;
                                    inspectedColor = null; selectSample = !selectSample; sampleBounds = null },
                                    enabled = selectSample || (image != null && !loading && !playing && previewPosition == position)) {
                                    Text(if (selectSample) "Отменить выделение" else "Подобрать шрифт по образцу")
                                }
                                Text("Библиотека шрифтов", style = MaterialTheme.typography.titleMedium)
                                if (!fontsReady) Text("Подготавливаем библиотеку шрифтов…")
                                if (fontsReady) EditorFontPicker(layer.fontId) { update(layer.copy(fontId = it)) }
                            } else if (durationMs > 1) {
                                TextTimingControls(layer, position, durationMs,
                                    videoPlayer != null && videoPlayer.playerError == null && !minimal,
                                    onChange = ::update, onSeek = ::seek, onPlayInterval = {
                                        seek(layer.startMs)
                                        intervalEnd = layer.endMs ?: durationMs
                                        selectSample = false; sampleBounds = null
                                        pickColor = false; inspectedColor = null
                                        previewZoom = PreviewZoom()
                                        videoPlayer?.play(); playing = true
                                    })
                            }
                        }
                    }
                    if (expandedControls) selected?.let { layer ->
                        HorizontalDivider()
                        OutlinedButton(onClick = {
                            pickColor = false; inspectedColor = null; selectSample = false; sampleBounds = null
                            layers = layers.filterNot { it.id == layer.id }; selectedId = layers.lastOrNull()?.id
                        }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text("Удалить надпись", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        if (confirmExit) AlertDialog(onDismissRequest = { confirmExit = false },
            title = { Text("Отменить изменения текста?") },
            confirmButton = { OutlinedButton(onClick = onDismiss) { Text("Отменить изменения") } },
            dismissButton = { OutlinedButton(onClick = { confirmExit = false }) { Text("Продолжить") } })
    }
}

private fun textTime(ms: Long): String = "%d:%02d.%03d".format(ms / 60_000L, ms / 1000L % 60, ms % 1000)

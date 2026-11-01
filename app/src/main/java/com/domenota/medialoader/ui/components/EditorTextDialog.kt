package com.domenota.medialoader.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
    val softKeyboard = LocalSoftwareKeyboardController.current
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
    var sheetOpen by remember { mutableStateOf(false) }
    var textToolTab by remember { mutableStateOf(UnifiedTextTab.KEYBOARD) }
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
        Surface(Modifier.fillMaxSize(), color = Color(0xFF08090D)) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                val compact = maxHeight < 560.dp
                val minimal = maxHeight < 280.dp
                val panelHeight = when {
                    minimal -> 120.dp
                    compact -> 200.dp
                    textToolTab == UnifiedTextTab.STYLE -> (maxHeight * .31f).coerceIn(210.dp, 280.dp)
                    else -> (maxHeight * .39f).coerceIn(244.dp, 338.dp)
                }
                val panelBg = Color(0xFF202127)
                val accent = textEditorAccent
                Column(Modifier.fillMaxSize()) {
                    // Editing is an image-first workspace, not a second settings screen.
                    Row(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = ::close) {
                            Icon(Icons.Rounded.Close, "Отменить редактирование", tint = Color.White)
                        }
                        Text("Текст", style = MaterialTheme.typography.titleLarge,
                            color = Color.White, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            onApply(layers.filter { it.text.isNotBlank() }.mapNotNull {
                                if (durationMs == null) it else it.within(durationMs)
                            })
                        }, enabled = !matching) {
                            Text("Готово", color = if (matching) Color.Gray else accent,
                                style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    val image = preview
                    Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black).clipToBounds()
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
                            Canvas(Modifier.fillMaxSize()
                                .pointerInput(image, selectedId, layers, selectSample, pickColor, position, playing) {
                                    if (selectSample || pickColor || playing) return@pointerInput
                                    detectTapGestures { offset ->
                                        val scale = minOf(size.width.toFloat() / image.width,
                                            size.height.toFloat() / image.height)
                                        if (scale <= 0f) return@detectTapGestures
                                        val left = (size.width - image.width * scale) / 2f
                                        val top = (size.height - image.height * scale) / 2f
                                        val x = (offset.x - left) / (image.width * scale)
                                        val y = (offset.y - top) / (image.height * scale)
                                        val nearest = layers.filter { durationMs == null || it.visibleAt(position) }
                                            .minByOrNull { (it.centerX - x) * (it.centerX - x) +
                                                (it.centerY - y) * (it.centerY - y) }
                                        if (nearest != null &&
                                            kotlin.math.hypot(nearest.centerX - x, nearest.centerY - y) < .22f) {
                                            selectedId = nearest.id
                                            sheetOpen = false
                                        }
                                    }
                                }
                                .pointerInput(image, selectedId, selectSample, position, playing, loading) {
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
                                TextLayerSelectionOverlay(image.width, image.height, layer, ::update, previewZoom,
                                    onTap = { sheetOpen = true; textToolTab = UnifiedTextTab.KEYBOARD })
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

                    // A minimal playback strip is part of the same editor on video.
                    if (durationMs != null && durationMs > 0L && !minimal) {
                        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (videoPlayer != null) IconButton(onClick = {
                                if (videoPlayer.playWhenReady) {
                                    videoPlayer.pause(); playing = false
                                    position = videoPlayer.currentPosition.coerceIn(0L, durationMs)
                                } else {
                                    if (videoPlayer.playbackState == Player.STATE_ENDED) seek(0)
                                    videoPlayer.play(); playing = true
                                }
                            }) {
                                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    if (playing) "Пауза" else "Воспроизвести", tint = Color.White)
                            }
                            Text(textTime(position) + " / " + textTime(durationMs),
                                color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                            Slider(value = position.coerceIn(0L, durationMs).toFloat(),
                                onValueChange = { seek(it.toLong()) },
                                valueRange = 0f..durationMs.toFloat(),
                                modifier = Modifier.weight(1f))
                        }
                    }
                    Column(Modifier.fillMaxWidth().height(if (sheetOpen) panelHeight else 84.dp)
                        .background(panelBg, androidx.compose.foundation.shape.RoundedCornerShape(
                            topStart = 24.dp, topEnd = 24.dp))) {
                        Box(Modifier.fillMaxWidth().height(17.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.width(38.dp).height(4.dp)
                                .background(Color.White.copy(alpha = .28f),
                                    androidx.compose.foundation.shape.CircleShape))
                        }
                        if (!sheetOpen) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                TextButton(onClick = {
                                    val layer = TextLayer(startMs = 0L, endMs = durationMs)
                                    layers = layers + layer
                                    selectedId = layer.id
                                    sheetOpen = true
                                    textToolTab = UnifiedTextTab.KEYBOARD
                                }, enabled = layers.size < 8) {
                                    Icon(Icons.Rounded.Add, null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Надпись")
                                }
                                Spacer(Modifier.weight(1f))
                                if (selected != null) TextButton(onClick = {
                                    sheetOpen = true; textToolTab = UnifiedTextTab.KEYBOARD
                                }) {
                                    Text("Изменить")
                                    Icon(Icons.Rounded.KeyboardArrowUp, null)
                                }
                            }
                        } else {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                UnifiedTextTabs(selected = textToolTab,
                                    onSelect = {
                                        textToolTab = it
                                        if (it != UnifiedTextTab.KEYBOARD) softKeyboard?.hide()
                                        selectSample = false; pickColor = false
                                    },
                                    modifier = Modifier.weight(1f))
                                IconButton(onClick = { sheetOpen = false }) {
                                    Icon(Icons.Rounded.KeyboardArrowDown, "Свернуть панель",
                                        tint = Color.LightGray)
                                }
                            }
                            HorizontalDivider(color = Color.White.copy(alpha = .10f))
                            Column(Modifier.fillMaxWidth().weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                message?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall,
                                        color = Color.LightGray)
                                }
                                // Layer selection is shared by all tabs, not a secondary drawer.
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    layers.forEachIndexed { index, item ->
                                        FilterChip(selected = selectedId == item.id,
                                            onClick = { selectedId = item.id },
                                            label = { Text("Текст " + (index + 1)) })
                                    }
                                    OutlinedButton(onClick = {
                                        val layer = TextLayer(startMs = 0L, endMs = durationMs)
                                        layers = layers + layer
                                        selectedId = layer.id
                                        textToolTab = UnifiedTextTab.KEYBOARD
                                    }, enabled = layers.size < 8) {
                                        Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                                        Text("Добавить")
                                    }
                                }
                                selected?.let { layer ->
                                    if (durationMs != null && !layer.visibleAt(position)) {
                                        TextButton(onClick = { seek(layer.startMs) }) {
                                            Text("Перейти к кадру с надписью")
                                        }
                                    }
                                    when (textToolTab) {
                                        UnifiedTextTab.KEYBOARD -> {
                                            OutlinedTextField(layer.text,
                                                { update(layer.copy(text = it.take(500))) },
                                                modifier = Modifier.fillMaxWidth(),
                                                label = { Text("Надпись") },
                                                maxLines = 4)
                                            Text("Нажми на надпись на изображении, чтобы выделить её. " +
                                                "Потяни рамку для перемещения, угловой маркер — для размера и поворота.",
                                                color = Color.LightGray,
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                        UnifiedTextTab.STYLE -> {
                                            UnifiedTextStyle(layer = layer,
                                                onChange = ::update,
                                                enabled = !matching,
                                                onPickColor = if (image != null && !minimal) ({
                                                    videoPlayer?.let { seek(it.currentPosition) }
                                                    selectSample = false; sampleBounds = null
                                                    pickColor = true; inspectedColor = null
                                                }) else null)
                                        }
                                        UnifiedTextTab.FONT -> {
                                            Text("Шрифт", style = MaterialTheme.typography.titleSmall,
                                                color = Color.White)
                                            OutlinedButton(onClick = {
                                                videoPlayer?.pause(); playing = false
                                                pickColor = false; inspectedColor = null
                                                selectSample = !selectSample; sampleBounds = null
                                            }, enabled = selectSample ||
                                                (image != null && !loading && previewPosition == position)) {
                                                Icon(Icons.Rounded.DocumentScanner, null,
                                                    Modifier.size(18.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text(if (selectSample) "Отменить выделение"
                                                    else "Распознать шрифт на фото")
                                            }
                                            sample?.let { source ->
                                                OutlinedTextField(sampleText,
                                                    { sampleText = it.take(120); suggestions = emptyList() },
                                                    label = { Text("Распознанный текст") },
                                                    modifier = Modifier.fillMaxWidth())
                                                OutlinedButton(onClick = {
                                                    matching = true
                                                    val target = selectedId
                                                    scope.launch {
                                                        try { matchSample(source, sampleText, target) }
                                                        catch (cancelled: CancellationException) { throw cancelled }
                                                        catch (_: Exception) { message = "Не удалось сравнить шрифты." }
                                                        finally { matching = false }
                                                    }
                                                }, enabled = !matching && sampleText.isNotBlank()) {
                                                    Text(if (matching) "Сравниваем…" else "Найти похожие")
                                                }
                                            }
                                            if (suggestions.isNotEmpty()) {
                                                Text("Похожие шрифты", color = Color.White)
                                                suggestions.forEach { font ->
                                                    OutlinedButton(onClick = {
                                                        update(layer.copy(fontId = font.id))
                                                    }) {
                                                        Text(font.label,
                                                            fontFamily = FontFamily(EditorFonts.face(context, font.id)))
                                                    }
                                                }
                                            }
                                            Text("Библиотека шрифтов", style = MaterialTheme.typography.titleMedium,
                                                color = Color.White)
                                            if (!fontsReady) Text("Подготавливаем библиотеку шрифтов…")
                                            else EditorFontPicker(layer.fontId) { update(layer.copy(fontId = it)) }
                                        }
                                    }
                                    if (durationMs != null && durationMs > 1L && textToolTab == UnifiedTextTab.KEYBOARD) {
                                        FilterChip(selected = timingTab,
                                            onClick = { timingTab = !timingTab },
                                            label = { Text("Время появления") })
                                        if (timingTab) TextTimingControls(layer, position,
                                            durationMs, videoPlayer != null &&
                                                videoPlayer.playerError == null && !minimal,
                                            onChange = ::update, onSeek = ::seek,
                                            onPlayInterval = {
                                                seek(layer.startMs)
                                                intervalEnd = layer.endMs ?: durationMs
                                                previewZoom = PreviewZoom()
                                                videoPlayer?.play(); playing = true
                                            })
                                    }
                                    TextButton(onClick = {
                                        layers = layers.filterNot { it.id == layer.id }
                                        selectedId = layers.lastOrNull()?.id
                                        if (selectedId == null) sheetOpen = false
                                    }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Удалить надпись", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
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

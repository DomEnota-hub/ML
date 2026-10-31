package com.domenota.medialoader.ui.screens

import android.graphics.Bitmap
import android.graphics.Paint as NativePaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.data.editor.*
import com.domenota.medialoader.ui.components.AppSlider
import com.domenota.medialoader.ui.components.EditorWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val mosaicAccent = Color(0xFFFFBD19)

/** Brush, rectangle or oval masks with a common renderer for preview/export. */
@Composable
internal fun PhotoLocalEffectsDialog(
    source: Bitmap,
    initial: List<PhotoLocalStroke>,
    onApply: (List<PhotoLocalStroke>) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by remember { mutableStateOf(PhotoLocalEffectKind.SQUARE) }
    var selection by remember { mutableStateOf(PhotoLocalSelection.BRUSH) }
    var brushFraction by remember { mutableFloatStateOf(.07f) }
    var intensity by remember { mutableFloatStateOf(.035f) }
    var strokes by remember(initial) { mutableStateOf(initial) }
    var active by remember { mutableStateOf<List<PhotoPoint>>(emptyList()) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var processed by remember(source) { mutableStateOf<Bitmap?>(null) }
    var panelOpen by remember { mutableStateOf(false) }
    var undo by remember { mutableStateOf<List<List<PhotoLocalStroke>>>(emptyList()) }
    var redo by remember { mutableStateOf<List<List<PhotoLocalStroke>>>(emptyList()) }

    fun commit(stroke: PhotoLocalStroke) {
        if (strokes.size >= PhotoLocalEffects.MAX_STROKES) return
        undo = (undo + listOf(strokes)).takeLast(32)
        strokes = strokes + stroke
        redo = emptyList()
    }

    LaunchedEffect(source, strokes) {
        delay(60L)
        val updated = withContext(Dispatchers.Default) {
            PhotoLocalEffects.apply(source, strokes)
        }
        processed = updated
    }
    DisposableEffect(processed) {
        val owned = processed
        onDispose {
            if (owned != null && owned !== source && !owned.isRecycled) owned.recycle()
        }
    }

    EditorWindow(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, "Закрыть мозаику", tint = Color.White)
                }
                Text("Мозаика", modifier = Modifier.weight(1f),
                    color = Color.White, fontSize = 18.sp)
                IconButton(onClick = {
                    if (undo.isNotEmpty()) {
                        redo = (redo + listOf(strokes)).takeLast(32)
                        strokes = undo.last()
                        undo = undo.dropLast(1)
                    }
                }, enabled = undo.isNotEmpty()) { Icon(Icons.Rounded.Undo, "Отменить") }
                IconButton(onClick = {
                    if (redo.isNotEmpty()) {
                        undo = (undo + listOf(strokes)).takeLast(32)
                        strokes = redo.last()
                        redo = redo.dropLast(1)
                    }
                }, enabled = redo.isNotEmpty()) { Icon(Icons.Rounded.Redo, "Повторить") }
                TextButton(onClick = { onApply(strokes) }, enabled = strokes != initial) {
                    Text("Готово", color = if (strokes != initial) mosaicAccent else Color.Gray)
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Image((processed ?: source).asImageBitmap(), "Предпросмотр мозаики",
                    Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                Canvas(Modifier.fillMaxSize().onSizeChanged { viewport = it }
                    .pointerInput(source, selection, kind, viewport) {
                        if (viewport.width <= 0 || viewport.height <= 0) return@pointerInput
                        val rect = photoCanvasRect(viewport.width.toFloat(), viewport.height.toFloat(),
                            source.width, source.height)
                        detectTapGestures { position ->
                            if (selection == PhotoLocalSelection.BRUSH &&
                                rect.inside(position.x, position.y)) {
                                commit(PhotoLocalStroke(kind, selection,
                                    listOf(rect.normalize(position.x, position.y)),
                                    brushFraction, intensity))
                            }
                        }
                    }
                    .pointerInput(source, selection, kind, viewport) {
                        if (viewport.width <= 0 || viewport.height <= 0) return@pointerInput
                        val rect = photoCanvasRect(viewport.width.toFloat(), viewport.height.toFloat(),
                            source.width, source.height)
                        detectDragGestures(
                            onDragStart = { position ->
                                active = if (rect.inside(position.x, position.y))
                                    listOf(rect.normalize(position.x, position.y))
                                else emptyList()
                            },
                            onDrag = { change, _ ->
                                if (active.isNotEmpty()) {
                                    change.consume()
                                    val point = rect.normalize(change.position.x, change.position.y)
                                    active = if (selection == PhotoLocalSelection.BRUSH)
                                        (active + point).take(700)
                                    else listOf(active.first(), point)
                                }
                            },
                            onDragEnd = {
                                if (active.isNotEmpty() &&
                                    (selection == PhotoLocalSelection.BRUSH || active.size >= 2)) {
                                    commit(PhotoLocalStroke(kind, selection, active,
                                        brushFraction, intensity))
                                }
                                active = emptyList()
                            },
                            onDragCancel = { active = emptyList() },
                        )
                    }) {
                    if (active.isNotEmpty()) {
                        val rect = photoCanvasRect(size.width, size.height, source.width, source.height)
                        val stroke = PhotoLocalStroke(kind, selection,
                            if (selection != PhotoLocalSelection.BRUSH && active.size == 1)
                                listOf(active[0], active[0]) else active,
                            brushFraction, intensity)
                        val canvas = drawContext.canvas.nativeCanvas
                        val saved = canvas.save()
                        canvas.translate(rect.x, rect.y)
                        canvas.scale(rect.width / source.width, rect.height / source.height)
                        canvas.drawPath(PhotoLocalEffects.maskPath(stroke, source.width, source.height),
                            NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
                                color = android.graphics.Color.argb(100, 255, 189, 25)
                                style = NativePaint.Style.FILL
                            })
                        canvas.restoreToCount(saved)
                    }
                }
            }
            if (panelOpen) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp)
                    .clip(RoundedCornerShape(18.dp)).background(Color(0xFF252529))
                    .padding(horizontal = 13.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Размер выделения", color = Color.LightGray, fontSize = 11.sp)
                    AppSlider(brushFraction, { brushFraction = it },
                        valueRange = .01f..0.25f, modifier = Modifier.fillMaxWidth())
                    Text("Размер пикселя / сила размытия", color = Color.LightGray, fontSize = 11.sp)
                    AppSlider(intensity, { intensity = it },
                        valueRange = .005f..0.08f, modifier = Modifier.fillMaxWidth())
                }
            }
            Row(Modifier.fillMaxWidth().height(50.dp).horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                PhotoLocalSelection.entries.forEach { option ->
                    FilterChip(selected = selection == option,
                        onClick = { selection = option },
                        label = { Text(option.title) },
                        leadingIcon = {
                            Icon(when (option) {
                                PhotoLocalSelection.BRUSH -> Icons.Rounded.Brush
                                PhotoLocalSelection.RECTANGLE -> Icons.Rounded.CropSquare
                                PhotoLocalSelection.OVAL -> Icons.Rounded.RadioButtonUnchecked
                            }, null, Modifier.size(17.dp))
                        })
                }
            }
            Row(Modifier.fillMaxWidth().height(75.dp).horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                PhotoLocalEffectKind.entries.forEach { option ->
                    Surface(onClick = {
                        if (kind == option) panelOpen = !panelOpen
                        else { kind = option; panelOpen = true }
                    }, modifier = Modifier.width(102.dp).height(63.dp),
                        color = if (kind == option) mosaicAccent else Color(0xFF2C2C31),
                        shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(when (option) {
                                PhotoLocalEffectKind.SQUARE -> Icons.Rounded.GridOn
                                PhotoLocalEffectKind.STAGGERED -> Icons.Rounded.ViewModule
                                PhotoLocalEffectKind.BLUR -> Icons.Rounded.BlurOn
                            }, contentDescription = option.title,
                                tint = if (kind == option) Color.Black else Color.White)
                            Text(option.title, fontSize = 11.sp,
                                color = if (kind == option) Color.Black else Color.White)
                        }
                    }
                }
                OutlinedButton(onClick = { panelOpen = !panelOpen }) {
                    Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Параметры")
                }
            }
        }
    }
}

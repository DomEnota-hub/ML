package com.domenota.medialoader.ui.screens

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
import kotlin.math.min

/** The same photo/image geometry is used for the drawing overlay and gesture input. */
internal data class PhotoCanvasRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    fun inside(px: Float, py: Float) = px >= x && py >= y && px <= x + width && py <= y + height
    fun normalize(px: Float, py: Float): PhotoPoint =
        PhotoDrawing.clamp((px - x) / width, (py - y) / height)
}

internal fun photoCanvasRect(viewWidth: Float, viewHeight: Float, imageWidth: Int, imageHeight: Int): PhotoCanvasRect {
    require(viewWidth > 0f && viewHeight > 0f && imageWidth > 0 && imageHeight > 0)
    val scale = min(viewWidth / imageWidth, viewHeight / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    return PhotoCanvasRect((viewWidth - width) / 2f, (viewHeight - height) / 2f, width, height)
}

/** The tools themselves stay independent of panels and saved vector geometry. */
private enum class DrawingTool(val label: String, val kind: PhotoMarkKind?) {
    PEN("Карандаш", PhotoMarkKind.PEN),
    MARKER("Маркер", PhotoMarkKind.MARKER),
    NEON("Неон", PhotoMarkKind.NEON),
    ERASER("Ластик", null),
    SELECT("Выбрать", null),
    LINE("Линия", PhotoMarkKind.LINE),
    RECTANGLE("Рамка", PhotoMarkKind.RECTANGLE),
    OVAL("Овал", PhotoMarkKind.OVAL),
    ARROW("Стрелка", PhotoMarkKind.ARROW),
}

private val editorYellow = Color(0xFFFFBD19)
private val quickColors = listOf(
    AndroidColor.WHITE, AndroidColor.GRAY, AndroidColor.BLACK,
    0xFFFF313C.toInt(), 0xFFFF9818.toInt(), AndroidColor.YELLOW,
    0xFF71CF39.toInt(), 0xFF37ACF9.toInt(), 0xFF914FDC.toInt(),
)

/**
 * A draft vector editor: editing an existing object never mutates the original
 * image; the same PhotoDrawing renderer is used at export resolution.
 */
@Composable
internal fun PhotoDrawDialog(
    preview: Bitmap,
    initial: List<PhotoMark> = emptyList(),
    textLayers: List<TextLayer> = emptyList(),
    onApply: (List<PhotoMark>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var tool by remember { mutableStateOf(DrawingTool.PEN) }
    var color by remember { mutableIntStateOf(AndroidColor.WHITE) }
    var thickness by remember { mutableFloatStateOf(.008f) }
    var marks by remember(initial) { mutableStateOf(initial) }
    var selected by remember { mutableIntStateOf(-1) }
    var active by remember { mutableStateOf<List<PhotoPoint>>(emptyList()) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var panelOpen by remember { mutableStateOf(false) }
    var paletteOpen by remember { mutableStateOf(false) }
    var undo by remember { mutableStateOf<List<List<PhotoMark>>>(emptyList()) }
    var redo by remember { mutableStateOf<List<List<PhotoMark>>>(emptyList()) }
    var beforeGesture by remember { mutableStateOf<List<PhotoMark>?>(null) }
    var gestureRole by remember { mutableStateOf("move") }

    fun push(before: List<PhotoMark>) {
        if (before != marks) {
            undo = (undo + listOf(before)).takeLast(32)
            redo = emptyList()
        }
    }
    fun change(next: List<PhotoMark>) {
        if (next != marks) { val before = marks; marks = next; push(before) }
    }
    fun replaceSelected(update: (PhotoMark) -> PhotoMark) {
        if (selected in marks.indices) {
            val next = marks.toMutableList()
            next[selected] = update(next[selected])
            change(next)
        }
    }
    val selectedMark = marks.getOrNull(selected)

    EditorWindow(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Row(Modifier.fillMaxWidth().height(57.dp).padding(horizontal = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Отмена", tint = Color.White) }
                Text("Рисование", modifier = Modifier.weight(1f), color = Color.White,
                    fontSize = 17.sp)
                IconButton(onClick = {
                    if (undo.isNotEmpty()) {
                        redo = (redo + listOf(marks)).takeLast(32)
                        marks = undo.last(); undo = undo.dropLast(1); selected = -1
                    }
                }, enabled = undo.isNotEmpty()) { Icon(Icons.Rounded.Undo, "Отменить") }
                IconButton(onClick = {
                    if (redo.isNotEmpty()) {
                        undo = (undo + listOf(marks)).takeLast(32)
                        marks = redo.last(); redo = redo.dropLast(1); selected = -1
                    }
                }, enabled = redo.isNotEmpty()) { Icon(Icons.Rounded.Redo, "Повторить") }
                TextButton(onClick = { onApply(marks) }, enabled = marks != initial) {
                    Text("Готово", color = if (marks != initial) editorYellow else Color.Gray)
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black),
                contentAlignment = Alignment.Center) {
                Image(preview.asImageBitmap(), "Изображение для рисования",
                    Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                Canvas(Modifier.fillMaxSize().onSizeChanged { viewport = it }
                    .pointerInput(preview, tool, viewport) {
                        if (viewport.width <= 0 || viewport.height <= 0) return@pointerInput
                        val rect = photoCanvasRect(viewport.width.toFloat(), viewport.height.toFloat(),
                            preview.width, preview.height)
                        detectTapGestures { offset ->
                            if (!rect.inside(offset.x, offset.y)) return@detectTapGestures
                            val point = rect.normalize(offset.x, offset.y)
                            if (tool == DrawingTool.SELECT) {
                                selected = PhotoMarkEdits.pick(marks, point)
                            } else if (tool == DrawingTool.ERASER) {
                                selected = -1
                                change(PhotoMarkEdits.eraseNear(marks, point, thickness * 3f))
                            }
                        }
                    }
                    .pointerInput(preview, tool, viewport) {
                        if (viewport.width <= 0 || viewport.height <= 0) return@pointerInput
                        val rect = photoCanvasRect(viewport.width.toFloat(), viewport.height.toFloat(),
                            preview.width, preview.height)
                        detectDragGestures(
                            onDragStart = { at ->
                                if (!rect.inside(at.x, at.y)) {
                                    active = emptyList()
                                    beforeGesture = null
                                } else {
                                    val point = rect.normalize(at.x, at.y)
                                    beforeGesture = marks
                                    when (tool) {
                                        DrawingTool.SELECT -> {
                                            selected = PhotoMarkEdits.pick(marks, point)
                                            val current = marks.getOrNull(selected)
                                            val bounds = current?.let(PhotoMarkEdits::bounds)
                                            gestureRole = when {
                                                current == null -> "move"
                                                current.kind in listOf(PhotoMarkKind.PEN,
                                                    PhotoMarkKind.MARKER, PhotoMarkKind.NEON) -> "move"
                                                bounds != null &&
                                                    kotlin.math.abs(point.x - bounds.right) < .05f &&
                                                    kotlin.math.abs(point.y - bounds.bottom) < .05f -> "resize"
                                                bounds != null &&
                                                    kotlin.math.abs(point.x - bounds.right) < .05f &&
                                                    kotlin.math.abs(point.y - bounds.top) < .05f -> "rotate"
                                                else -> "move"
                                            }
                                        }
                                        DrawingTool.ERASER -> {
                                            selected = -1
                                            marks = PhotoMarkEdits.eraseNear(marks, point, thickness * 3f)
                                        }
                                        else -> { selected = -1; active = listOf(point) }
                                    }
                                }
                            },
                            onDrag = { change, dragAmount ->
                                if (!rect.inside(change.position.x, change.position.y) &&
                                    tool != DrawingTool.SELECT) return@detectDragGestures
                                val point = rect.normalize(change.position.x, change.position.y)
                                when (tool) {
                                    DrawingTool.SELECT -> {
                                        if (selected in marks.indices) {
                                            val next = marks.toMutableList()
                                            val mark = next[selected]
                                            next[selected] = when (gestureRole) {
                                                "resize" -> PhotoMarkEdits.resize(mark, point)
                                                "rotate" -> PhotoMarkEdits.rotate(mark,
                                                    dragAmount.x / rect.width * 180f)
                                                else -> PhotoMarkEdits.move(mark,
                                                    dragAmount.x / rect.width, dragAmount.y / rect.height)
                                            }
                                            marks = next
                                            change.consume()
                                        }
                                    }
                                    DrawingTool.ERASER -> {
                                        marks = PhotoMarkEdits.eraseNear(marks, point, thickness * 3f)
                                        change.consume()
                                    }
                                    else -> {
                                        if (active.isNotEmpty()) {
                                            change.consume()
                                            active = if (tool.kind in listOf(PhotoMarkKind.PEN,
                                                PhotoMarkKind.MARKER, PhotoMarkKind.NEON))
                                                (active + point).take(900)
                                            else listOf(active.first(), point)
                                        }
                                    }
                                }
                            },
                            onDragEnd = {
                                if (active.isNotEmpty() && tool.kind != null &&
                                    marks.size < PhotoDrawing.MAX_MARKS) {
                                    marks = marks + PhotoMark(tool.kind!!, active.take(900),
                                        color, thickness)
                                }
                                beforeGesture?.let(::push)
                                beforeGesture = null
                                active = emptyList()
                            },
                            onDragCancel = {
                                // An interrupted gesture must not create half a shape.
                                beforeGesture?.let { marks = it }
                                beforeGesture = null; active = emptyList()
                            },
                        )
                    }) {
                    if (viewport.width > 0 && viewport.height > 0) {
                        val rect = photoCanvasRect(size.width, size.height, preview.width, preview.height)
                        val canvas = drawContext.canvas.nativeCanvas
                        val save = canvas.save()
                        canvas.translate(rect.x, rect.y)
                        canvas.scale(rect.width / preview.width, rect.height / preview.height)
                        val current = if (active.isNotEmpty() && tool.kind != null)
                            marks + PhotoMark(tool.kind!!, active.take(900), color, thickness)
                            else marks
                        PhotoDrawing.draw(canvas, preview.width, preview.height, current)
                        if (textLayers.isNotEmpty()) {
                            TextLayerRenderer.draw(context, canvas, preview.width, preview.height,
                                textLayers, includeAll = true)
                        }
                        if (tool == DrawingTool.SELECT && selected in marks.indices) {
                            val bounds = PhotoMarkEdits.bounds(marks[selected])
                            val stroke = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                                color = AndroidColor.WHITE; style = AndroidPaint.Style.STROKE
                                strokeWidth = min(preview.width, preview.height) * .003f
                                pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 8f), 0f)
                            }
                            canvas.drawRect(bounds.left * preview.width, bounds.top * preview.height,
                                bounds.right * preview.width, bounds.bottom * preview.height, stroke)
                            val handle = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                                color = AndroidColor.YELLOW
                                style = AndroidPaint.Style.FILL
                            }
                            val radius = min(preview.width, preview.height) * .012f
                            canvas.drawCircle(bounds.right * preview.width,
                                bounds.bottom * preview.height, radius, handle)
                            canvas.drawCircle(bounds.right * preview.width,
                                bounds.top * preview.height, radius, handle)
                        }
                        canvas.restoreToCount(save)
                    }
                }
            }
            if (panelOpen) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp)
                    .clip(RoundedCornerShape(19.dp)).background(Color(0xFF222226))
                    .padding(horizontal = 15.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (tool == DrawingTool.ERASER) "Размер ластика" else "Толщина",
                            color = Color.White, fontSize = 12.sp, modifier = Modifier.width(106.dp))
                        AppSlider(thickness, { next ->
                            thickness = next
                        }, modifier = Modifier.weight(1f), valueRange = .002f..05f)
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        quickColors.forEach { option ->
                            Surface(onClick = {
                                color = option
                                if (selected in marks.indices) replaceSelected { it.copy(color = option) }
                            }, shape = CircleShape,
                                modifier = Modifier.size(if (color == option) 35.dp else 30.dp),
                                color = Color(option),
                                border = if (color == option) androidx.compose.foundation.BorderStroke(
                                    2.dp, editorYellow) else null) {}
                        }
                        IconButton(onClick = { paletteOpen = true }) {
                            Icon(Icons.Rounded.Palette, "Расширенная палитра",
                                tint = Color.White)
                        }
                    }
                    if (tool == DrawingTool.SELECT && selectedMark != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            OutlinedButton(onClick = {
                                replaceSelected { PhotoMarkEdits.rotate(it, -15f) }
                            }) { Text("↶ 15°") }
                            OutlinedButton(onClick = {
                                replaceSelected { PhotoMarkEdits.rotate(it, 15f) }
                            }) { Text("15° ↷") }
                            OutlinedButton(onClick = {
                                if (selected in marks.indices) {
                                    change(marks.filterIndexed { index, _ -> index != selected })
                                    selected = -1
                                }
                            }) { Text("Удалить") }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(80.dp).horizontalScroll(rememberScrollState())
                .padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                DrawingTool.entries.forEach { option ->
                    val selectedTool = tool == option
                    val icon = when (option) {
                        DrawingTool.PEN -> Icons.Rounded.Edit
                        DrawingTool.MARKER -> Icons.Rounded.BorderColor
                        DrawingTool.NEON -> Icons.Rounded.AutoAwesome
                        DrawingTool.ERASER -> Icons.Rounded.AutoFixNormal
                        DrawingTool.SELECT -> Icons.Rounded.OpenWith
                        DrawingTool.LINE -> Icons.Rounded.HorizontalRule
                        DrawingTool.RECTANGLE -> Icons.Rounded.CropSquare
                        DrawingTool.OVAL -> Icons.Rounded.RadioButtonUnchecked
                        DrawingTool.ARROW -> Icons.Rounded.NorthEast
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(67.dp)) {
                        Surface(
                            onClick = {
                                if (tool == option) panelOpen = !panelOpen
                                else { tool = option; panelOpen = true }
                                selected = -1
                            }, shape = RoundedCornerShape(14.dp),
                            color = if (selectedTool) editorYellow else Color(0xFF29292E),
                            modifier = Modifier.size(52.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(icon, contentDescription = option.label, modifier = Modifier.size(29.dp),
                                    tint = if (selectedTool) Color.Black else
                                        when (option) {
                                            DrawingTool.MARKER -> Color(0xFFFA8D9C)
                                            DrawingTool.NEON -> Color(0xFFBF70FF)
                                            DrawingTool.ERASER -> Color(0xFF9AD8FF)
                                            else -> Color.White
                                        })
                            }
                        }
                        Text(option.label, color = Color.LightGray, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
        }
    }
    if (paletteOpen) {
        AlertDialog(onDismissRequest = { paletteOpen = false },
            title = { Text("Палитра") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val shades = listOf(AndroidColor.WHITE, 0xFFDDDDDD.toInt(),
                        0xFFAAAAAA.toInt(), 0xFF777777.toInt(), 0xFF444444.toInt(),
                        AndroidColor.BLACK)
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        shades.forEach { sample ->
                            Surface(onClick = {
                                color = sample
                                if (selected in marks.indices) replaceSelected { it.copy(color = sample) }
                                paletteOpen = false
                            }, shape = CircleShape, modifier = Modifier.size(34.dp), color = Color(sample)) {}
                        }
                    }
                    repeat(6) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            repeat(10) { col ->
                                val sample = AndroidColor.HSVToColor(floatArrayOf(
                                    col * 36f, (.38f + row * .12f).coerceAtMost(1f), 1f))
                                Surface(onClick = {
                                    color = sample
                                    if (selected in marks.indices) replaceSelected { it.copy(color = sample) }
                                    paletteOpen = false
                                }, shape = RoundedCornerShape(3.dp),
                                    modifier = Modifier.size(23.dp), color = Color(sample)) {}
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { paletteOpen = false }) { Text("Готово") } })
    }
}

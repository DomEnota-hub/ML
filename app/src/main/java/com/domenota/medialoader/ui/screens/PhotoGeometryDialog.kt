package com.domenota.medialoader.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.data.editor.PhotoGeometry
import com.domenota.medialoader.data.editor.PhotoGeometryMath
import com.domenota.medialoader.data.editor.PhotoGeometryPoint
import com.domenota.medialoader.ui.components.EditorWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.hypot

private enum class GeometryMode(val title: String) {
    STRAIGHTEN("Горизонт"), HORIZONTAL("Горизонтальная"),
    VERTICAL("Вертикальная"), FREE("Свободно")
}
private val geoAccent = Color(0xFFFFBD19)

@Composable
internal fun PhotoGeometryDialog(
    source: Bitmap,
    initial: PhotoGeometry,
    onApply: (PhotoGeometry) -> Unit,
    onDismiss: () -> Unit,
) {
    var geometry by remember(source) { mutableStateOf(initial) }
    var mode by remember { mutableStateOf(GeometryMode.STRAIGHTEN) }
    var showGuides by remember { mutableStateOf(true) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var activeCorner by remember { mutableIntStateOf(-1) }
    var rendered by remember(source) { mutableStateOf<Bitmap?>(null) }
    var undo by remember { mutableStateOf<List<PhotoGeometry>>(emptyList()) }
    var redo by remember { mutableStateOf<List<PhotoGeometry>>(emptyList()) }
    var beforeDrag by remember { mutableStateOf<PhotoGeometry?>(null) }
    val current by rememberUpdatedState(geometry)

    fun commit(next: PhotoGeometry) {
        if (next == geometry) return
        undo = (undo + geometry).takeLast(40)
        redo = emptyList()
        geometry = next
    }
    LaunchedEffect(source, geometry) {
        delay(75L)
        val next = withContext(Dispatchers.Default) {
            PhotoGeometryMath.apply(source, geometry)
        }
        rendered = next
    }
    DisposableEffect(rendered) {
        val owned = rendered
        onDispose {
            if (owned !== source && owned != null && !owned.isRecycled) owned.recycle()
        }
    }

    EditorWindow(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, "Отмена", tint = Color.White)
                }
                Text("Геометрия", modifier = Modifier.weight(1f),
                    color = Color.White, fontSize = 18.sp)
                IconButton(enabled = undo.isNotEmpty(), onClick = {
                    if (undo.isNotEmpty()) {
                        redo = (redo + geometry).takeLast(40)
                        geometry = undo.last(); undo = undo.dropLast(1)
                    }
                }) { Icon(Icons.Rounded.Undo, "Отменить изменение") }
                IconButton(enabled = redo.isNotEmpty(), onClick = {
                    if (redo.isNotEmpty()) {
                        undo = (undo + geometry).takeLast(40)
                        geometry = redo.last(); redo = redo.dropLast(1)
                    }
                }) { Icon(Icons.Rounded.Redo, "Повторить изменение") }
                TextButton(onClick = { onApply(geometry) },
                    enabled = geometry != initial) {
                    Text("Готово", color = if (geometry != initial) geoAccent else Color.Gray)
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Image((rendered ?: source).asImageBitmap(), contentDescription = "Геометрия фотографии",
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize().onSizeChanged { viewport = it }
                    .pointerInput(mode, viewport, source) {
                        if (mode != GeometryMode.FREE || viewport.width <= 0 ||
                            viewport.height <= 0) return@pointerInput
                        val rect = photoCanvasRect(viewport.width.toFloat(),
                            viewport.height.toFloat(), source.width, source.height)
                        detectDragGestures(
                            onDragStart = { pos ->
                                val corners = current.corners
                                activeCorner = corners.indices.minByOrNull { index ->
                                    val corner = corners[index]
                                    hypot(rect.x + rect.width * corner.x - pos.x,
                                        rect.y + rect.height * corner.y - pos.y)
                                }?.takeIf { index ->
                                    val corner = corners[index]
                                    hypot(rect.x + rect.width * corner.x - pos.x,
                                        rect.y + rect.height * corner.y - pos.y) < 65.dp.toPx()
                                } ?: -1
                                beforeDrag = if (activeCorner >= 0) current else null
                            },
                            onDrag = { pointer, _ ->
                                if (activeCorner >= 0 && rect.inside(
                                        pointer.position.x.coerceIn(rect.x, rect.x + rect.width),
                                        pointer.position.y.coerceIn(rect.y, rect.y + rect.height))) {
                                    val at = rect.normalize(pointer.position.x, pointer.position.y)
                                    geometry = PhotoGeometryMath.moveCorner(current, activeCorner,
                                        PhotoGeometryPoint(at.x, at.y))
                                    pointer.consume()
                                }
                            },
                            onDragEnd = {
                                beforeDrag?.let { previous ->
                                    if (previous != geometry) {
                                        undo = (undo + previous).takeLast(40)
                                        redo = emptyList()
                                    }
                                }
                                activeCorner = -1
                                beforeDrag = null
                            },
                            onDragCancel = {
                                beforeDrag?.let { geometry = it }
                                beforeDrag = null; activeCorner = -1
                            },
                        )
                    }) {
                    if (!showGuides || viewport.width == 0 || viewport.height == 0)
                        return@Canvas
                    val rect = photoCanvasRect(size.width, size.height,
                        source.width, source.height)
                    val corners = geometry.corners.map {
                        Offset(rect.x + rect.width * it.x, rect.y + rect.height * it.y)
                    }
                    val outline = if (mode == GeometryMode.FREE) geoAccent
                        else Color.White.copy(alpha = .70f)
                    corners.indices.forEach { i ->
                        drawLine(outline, corners[i], corners[(i + 1) % 4], 2.dp.toPx())
                    }
                    for (fraction in 1..2) {
                        val t = fraction / 3f
                        val left = Offset(
                            corners[0].x * (1f - t) + corners[3].x * t,
                            corners[0].y * (1f - t) + corners[3].y * t)
                        val right = Offset(
                            corners[1].x * (1f - t) + corners[2].x * t,
                            corners[1].y * (1f - t) + corners[2].y * t)
                        val top = Offset(
                            corners[0].x * (1f - t) + corners[1].x * t,
                            corners[0].y * (1f - t) + corners[1].y * t)
                        val bottom = Offset(
                            corners[3].x * (1f - t) + corners[2].x * t,
                            corners[3].y * (1f - t) + corners[2].y * t)
                        drawLine(Color.White.copy(alpha = .32f), left, right, 1.dp.toPx())
                        drawLine(Color.White.copy(alpha = .32f), top, bottom, 1.dp.toPx())
                    }
                    if (mode == GeometryMode.FREE) corners.forEachIndexed { index, p ->
                        drawCircle(Color.Black, radius = 11.dp.toPx(), center = p)
                        drawCircle(geoAccent, radius = if (activeCorner == index) 9.dp.toPx()
                            else 7.dp.toPx(), center = p,
                            style = Stroke(width = 3.dp.toPx()))
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
                if (mode == GeometryMode.FREE) {
                    Text("Потяни углы рамки для коррекции перспективы",
                        color = Color.LightGray, fontSize = 12.sp)
                } else {
                    val value = when (mode) {
                        GeometryMode.STRAIGHTEN -> geometry.straighten
                        GeometryMode.HORIZONTAL -> geometry.horizontalPerspective
                        GeometryMode.VERTICAL -> geometry.verticalPerspective
                        GeometryMode.FREE -> 0f
                    }
                    val range = if (mode == GeometryMode.STRAIGHTEN) -45f..45f else -50f..50f
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (mode == GeometryMode.STRAIGHTEN) "Угол" else "Перспектива",
                            color = Color.LightGray, fontSize = 12.sp)
                        Slider(value, onValueChange = { n ->
                            if (beforeDrag == null) beforeDrag = geometry
                            geometry = when (mode) {
                                GeometryMode.STRAIGHTEN -> geometry.copy(straighten = n)
                                GeometryMode.HORIZONTAL -> geometry.copy(horizontalPerspective = n)
                                GeometryMode.VERTICAL -> geometry.copy(verticalPerspective = n)
                                GeometryMode.FREE -> geometry
                            }
                        }, onValueChangeFinished = {
                            beforeDrag?.let { prior ->
                                if (prior != geometry) {
                                    undo = (undo + prior).takeLast(40)
                                    redo = emptyList()
                                }
                            }
                            beforeDrag = null
                        }, valueRange = range, modifier = Modifier.weight(1f)
                            .padding(horizontal = 10.dp))
                        Text("%.1f°".format(value),
                            fontSize = 12.sp, color = Color.White)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(70.dp).horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GeometryMode.entries.forEach { option ->
                    FilterChip(selected = mode == option, onClick = {
                        mode = option
                    }, label = { Text(option.title) })
                }
                IconButton(onClick = { showGuides = !showGuides }) {
                    Icon(Icons.Rounded.GridOn, "Сетка", tint = Color.White)
                }
                IconButton(onClick = { commit(PhotoGeometry()) }) {
                    Icon(Icons.Rounded.RestartAlt, "Сбросить геометрию", tint = Color.White)
                }
            }
        }
    }
}

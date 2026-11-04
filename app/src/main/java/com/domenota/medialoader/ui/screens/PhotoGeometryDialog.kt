package com.domenota.medialoader.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.data.editor.PhotoGeometry
import com.domenota.medialoader.data.editor.PhotoGeometryMath
import com.domenota.medialoader.data.editor.PhotoGeometryPoint
import com.domenota.medialoader.ui.components.AppSlider as Slider
import com.domenota.medialoader.ui.components.EditorWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

private enum class GeometryMode(val title: String) {
    STRAIGHTEN("Горизонт"), HORIZONTAL("Горизонтально"),
    VERTICAL("Вертикально"), DISTORTION("Искажение"), FREE("Свободно")
}
private val geoAccent = Color(0xFFFFBD19)

/** Geometry guides stay fixed; hit testing uses the original image rectangle. */
internal fun geometryCornerHitIndex(
    corners: List<PhotoGeometryPoint>, rect: PhotoCanvasRect,
    touch: Offset, radiusPx: Float,
): Int {
    if (corners.size != 4 || rect.width <= 0f || rect.height <= 0f) return -1
    val nearest = corners.indices.minByOrNull { index ->
        val point = corners[index]
        hypot(rect.x + rect.width * point.x - touch.x,
            rect.y + rect.height * point.y - touch.y)
    } ?: return -1
    val point = corners[nearest]
    return nearest.takeIf {
        hypot(rect.x + rect.width * point.x - touch.x,
            rect.y + rect.height * point.y - touch.y) <= radiusPx
    } ?: -1
}

internal fun geometryPreviewSize(width: Int, height: Int, maxEdge: Int = 960): Pair<Int, Int> {
    require(width > 0 && height > 0 && maxEdge > 0)
    val longest = max(width, height)
    if (longest <= maxEdge) return width to height
    val scale = maxEdge.toFloat() / longest
    return (width * scale).roundToInt().coerceAtLeast(1) to
        (height * scale).roundToInt().coerceAtLeast(1)
}

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
    var renderedGeometry by remember(source) { mutableStateOf(initial) }
    var reducedSource by remember(source) { mutableStateOf<Bitmap?>(null) }
    val zoom = remember(source) { PhotoViewportState() }
    val density = LocalDensity.current
    val cornerHitRadius = with(density) { 112.dp.toPx() }
    val transforms = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (zoom.scale * zoomChange).coerceIn(1f, 5f)
        val limits = photoPanLimits(viewport.width.toFloat(), viewport.height.toFloat(),
            source.width, source.height, nextScale)
        zoom.scale = nextScale
        zoom.translation = clampPhotoPan(zoom.translation + panChange, limits)
    }
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
    // Render only a bounded preview while scrubbing: full source resolution is
    // transformed once during final export, not on each finger movement.
    LaunchedEffect(source) {
        val preview = withContext(Dispatchers.Default) {
            val (width, height) = geometryPreviewSize(source.width, source.height)
            if (width == source.width && height == source.height) source
            else Bitmap.createScaledBitmap(source, width, height, true)
        }
        reducedSource = preview
    }
    DisposableEffect(reducedSource) {
        val owned = reducedSource
        onDispose {
            if (owned != null && owned !== source && !owned.isRecycled) owned.recycle()
        }
    }
    // This producer is keyed only to source, not every value change. Unlike a
    // debounce it keeps publishing frames during a continuous drag instead of
    // waiting until the finger is lifted. Each pass uses the latest geometry.
    LaunchedEffect(reducedSource) {
        val input = reducedSource ?: return@LaunchedEffect
        while (true) {
            val requested = current
            if (rendered == null || requested != renderedGeometry) {
                val next = withContext(Dispatchers.Default) {
                    PhotoGeometryMath.apply(input, requested)
                }
                rendered = next
                renderedGeometry = requested
            }
            delay(16L)
        }
    }
    DisposableEffect(rendered) {
        val owned = rendered
        onDispose {
            if (owned !== source && owned !== reducedSource &&
                owned != null && !owned.isRecycled) owned.recycle()
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
            Box(Modifier.fillMaxWidth().weight(1f)
                .onSizeChanged {
                    viewport = it
                    zoom.translation = clampPhotoPan(zoom.translation,
                        photoPanLimits(it.width.toFloat(), it.height.toFloat(),
                            source.width, source.height, zoom.scale))
                }
                .transformable(transforms), contentAlignment = Alignment.Center) {
                // Keep guides fixed and show immediate affine feedback while the
                // reduced perspective bitmap is being calculated off the UI thread.
                Image((rendered ?: reducedSource ?: source).asImageBitmap(),
                    contentDescription = "Геометрия фотографии",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom.scale
                        scaleY = zoom.scale
                        translationX = zoom.translation.x
                        translationY = zoom.translation.y
                        rotationZ = geometry.straighten -
                            (if (rendered != null) renderedGeometry.straighten else 0f)
                    })
                Canvas(Modifier.fillMaxSize().testTag("geometry-canvas")
                    .pointerInput(mode, viewport, source) {
                        if (mode != GeometryMode.FREE || viewport.width <= 0 ||
                            viewport.height <= 0) return@pointerInput
                        val rect = photoCanvasRect(viewport.width.toFloat(),
                            viewport.height.toFloat(), source.width, source.height)
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val prior = current
                            val corner = geometryCornerHitIndex(prior.corners, rect, down.position, cornerHitRadius)
                            if (corner < 0) return@awaitEachGesture
                            val point = prior.corners[corner]
                            val grabOffset = Offset(rect.x + rect.width * point.x,
                                rect.y + rect.height * point.y) - down.position
                            activeCorner = corner
                            beforeDrag = prior
                            var completed = false
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    // Release the one-finger corner edit to the two-finger viewport gesture.
                                    if (event.changes.count { it.pressed } > 1) break
                                    val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (pointer.isConsumed) break
                                    if (!pointer.pressed) { completed = true; break }
                                    val position = pointer.position + grabOffset
                                    val at = rect.normalize(position.x.coerceIn(rect.x, rect.x + rect.width),
                                        position.y.coerceIn(rect.y, rect.y + rect.height))
                                    geometry = PhotoGeometryMath.moveCorner(current, corner, PhotoGeometryPoint(at.x, at.y))
                                    pointer.consume()
                                }
                            } finally {
                                if (completed && prior != geometry) {
                                    undo = (undo + prior).takeLast(40)
                                    redo = emptyList()
                                } else if (!completed) geometry = prior
                                activeCorner = -1
                                beforeDrag = null
                            }
                        }
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
                    Text("Потяни углы рамки; двумя пальцами — масштаб и сдвиг",
                        color = Color.LightGray, fontSize = 12.sp)
                } else {
                    val value = when (mode) {
                        GeometryMode.STRAIGHTEN -> geometry.straighten
                        GeometryMode.HORIZONTAL -> geometry.horizontalPerspective
                        GeometryMode.VERTICAL -> geometry.verticalPerspective
                        GeometryMode.DISTORTION -> geometry.distortion
                        GeometryMode.FREE -> 0f
                    }
                    val range = if (mode == GeometryMode.STRAIGHTEN) -45f..45f else -100f..100f
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(when (mode) { GeometryMode.STRAIGHTEN -> "Угол"; GeometryMode.DISTORTION -> "Искажение"; else -> "Перспектива" },
                            color = Color.LightGray, fontSize = 12.sp)
                        Text(
                            if (mode == GeometryMode.STRAIGHTEN) "${value.roundToInt()}°"
                            else value.roundToInt().toString(),
                            fontSize = 12.sp, color = Color.White
                        )
                    }
                    PhotoGeometryRuler(value = value, valueRange = range,
                            onValueChange = { n ->
                                if (beforeDrag == null) beforeDrag = geometry
                                geometry = when (mode) {
                                    GeometryMode.STRAIGHTEN -> geometry.copy(straighten = n)
                                    GeometryMode.HORIZONTAL -> geometry.copy(horizontalPerspective = n)
                                    GeometryMode.VERTICAL -> geometry.copy(verticalPerspective = n)
                                    GeometryMode.DISTORTION -> geometry.copy(distortion = n)
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
                            }, modifier = Modifier.fillMaxWidth())
                }
            }
            Row(Modifier.fillMaxWidth().height(70.dp).horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GeometryMode.entries.forEach { option ->
                    PhotoToolCard(option.title, when (option) {
                        GeometryMode.STRAIGHTEN -> PhotoGeometryIcons.horizon
                        GeometryMode.HORIZONTAL -> PhotoGeometryIcons.horizontal
                        GeometryMode.VERTICAL -> PhotoGeometryIcons.vertical
                        GeometryMode.DISTORTION -> PhotoGeometryIcons.distortion
                        GeometryMode.FREE -> PhotoGeometryIcons.free
                    }, mode == option, { mode = option }, enabled = true)
                }
                PhotoToolCard("Масштаб", Icons.Rounded.CenterFocusStrong, false, { zoom.reset() }, true)
                PhotoToolCard("Сетка", Icons.Rounded.GridOn, showGuides, { showGuides = !showGuides }, true)
                PhotoToolCard("Сбросить", Icons.Rounded.RestartAlt, false, { commit(PhotoGeometry()) }, true)
            }
        }
    }
}

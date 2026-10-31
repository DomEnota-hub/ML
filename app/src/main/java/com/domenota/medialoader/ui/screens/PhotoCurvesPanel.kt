package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.data.editor.*
import kotlin.math.abs
import kotlin.math.hypot

private fun curveChannelColor(channel: PhotoCurveChannel) = when (channel) {
    PhotoCurveChannel.RGB -> Color.White
    PhotoCurveChannel.RED -> Color(0xFFFF6363)
    PhotoCurveChannel.GREEN -> Color(0xFF69E88C)
    PhotoCurveChannel.BLUE -> Color(0xFF75A7FF)
}

@Composable
internal fun PhotoCurvesPanel(
    curves: PhotoCurves,
    enabled: Boolean,
    onLiveEdit: (PhotoCurves) -> Unit,
    onEditFinished: () -> Unit,
    onCommit: (PhotoCurves) -> Unit,
) {
    var channel by remember { mutableStateOf(PhotoCurveChannel.RGB) }
    var selection by remember(channel) { mutableIntStateOf(-1) }
    val curve = curves.forChannel(channel)
    val latestCurve by rememberUpdatedState(curve)
    val latestCurves by rememberUpdatedState(curves)
    val live by rememberUpdatedState(onLiveEdit)
    val finish by rememberUpdatedState(onEditFinished)
    val commit by rememberUpdatedState(onCommit)
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            PhotoCurveChannel.entries.forEach { c ->
                FilterChip(selected = c == channel, onClick = { channel = c },
                    enabled = enabled, label = { Text(c.title, fontSize = 10.sp) })
            }
        }
        Canvas(Modifier.fillMaxWidth().height(172.dp)
            .pointerInput(channel, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { p ->
                    val x = (p.x / size.width).coerceIn(0f, 1f)
                    val y = (1f - p.y / size.height).coerceIn(0f, 1f)
                    val points = latestCurve.points
                    val nearest = points.indices.minByOrNull { i ->
                        hypot(points[i].x - x, points[i].y - y)
                    } ?: -1
                    if (nearest >= 0 && hypot(points[nearest].x - x, points[nearest].y - y) < .055f) {
                        selection = nearest
                    } else {
                        val updated = latestCurve.insert(x, y)
                        if (updated != latestCurve) {
                            selection = updated.points.indexOfFirst { abs(it.x - x) < .002f }
                            commit(latestCurves.update(channel, updated))
                        }
                    }
                }
            }
            .pointerInput(channel, enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { p ->
                        val x = (p.x / size.width).coerceIn(0f, 1f)
                        val y = (1f - p.y / size.height).coerceIn(0f, 1f)
                        val pts = latestCurve.points
                        selection = pts.indices.minByOrNull { i ->
                            hypot(pts[i].x - x, pts[i].y - y)
                        }?.takeIf { hypot(pts[it].x - x, pts[it].y - y) <= .09f } ?: -1
                    },
                    onDrag = { change, _ ->
                        val index = selection
                        if (index >= 0 && index < latestCurve.points.size) {
                            change.consume()
                            val next = latestCurve.withPoint(index,
                                change.position.x / size.width,
                                1f - change.position.y / size.height)
                            live(latestCurves.update(channel, next))
                        }
                    },
                    onDragEnd = { if (selection >= 0) finish() },
                    onDragCancel = { if (selection >= 0) finish() },
                )
            }
        ) {
            val w = size.width; val h = size.height
            for (i in 0..4) {
                val k = i / 4f
                drawLine(Color.White.copy(alpha = .15f), Offset(k * w, 0f),
                    Offset(k * w, h), 1.dp.toPx())
                drawLine(Color.White.copy(alpha = .15f), Offset(0f, k * h),
                    Offset(w, k * h), 1.dp.toPx())
            }
            drawLine(Color.White.copy(alpha = .38f), Offset(0f, h), Offset(w, 0f),
                strokeWidth = 1.dp.toPx())
            val lookup = PhotoCurveMath.lut(curve)
            val path = Path().apply {
                moveTo(0f, h - lookup[0] / 255f * h)
                for (i in 1..255) lineTo(i / 255f * w, h - lookup[i] / 255f * h)
            }
            drawPath(path, curveChannelColor(channel), style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 2.dp.toPx(), cap = StrokeCap.Round))
            curve.points.forEachIndexed { index, p ->
                val center = Offset(p.x * w, (1f - p.y) * h)
                drawCircle(Color(0xFF252529), radius = if (selection == index) 8.dp.toPx()
                    else 6.dp.toPx(), center = center)
                drawCircle(curveChannelColor(channel), radius = if (selection == index) 5.dp.toPx()
                    else 3.5.dp.toPx(), center = center)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = {
                val next = curve.remove(selection)
                if (next != curve) { selection = -1; commit(curves.update(channel, next)) }
            }, enabled = enabled && selection in 1 until curve.points.lastIndex) {
                Text("Убрать точку", fontSize = 11.sp)
            }
            TextButton(onClick = {
                selection = -1; commit(curves.update(channel, PhotoCurve()))
            }, enabled = enabled && !curve.isNeutral()) {
                Text("Сбросить канал", fontSize = 11.sp)
            }
        }
    }
}

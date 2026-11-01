package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            PhotoCurveChannel.entries.forEach { c ->
                val selected = c == channel
                Box(Modifier.size(36.dp)
                    .border(if (selected) 3.dp else 1.dp,
                        if (selected) curveChannelColor(c) else Color.LightGray.copy(alpha = .55f), CircleShape)
                    .padding(5.dp)
                    .background(curveChannelColor(c), CircleShape)
                    .clickable(enabled = enabled) { channel = c },
                    contentAlignment = Alignment.Center) {}
            }
        }
        Canvas(Modifier.fillMaxWidth().height(172.dp)
            .pointerInput(channel, enabled) {
                if (!enabled) return@pointerInput
                // Single detector handles taps and drags. No competing tap/drag
                // recognizers can unexpectedly select the upper-right endpoint.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val height = size.height.toFloat().coerceAtLeast(1f)
                    val x = (down.position.x / width).coerceIn(0.002f, .998f)
                    val y = (1f - down.position.y / height).coerceIn(0f, 1f)
                    val points = latestCurve.points
                    val hitRadiusPx = 19.dp.toPx()
                    val hit = points.indices.minByOrNull { index ->
                        val p = points[index]
                        hypot(p.x * width - down.position.x,
                            (1f - p.y) * height - down.position.y)
                    }?.takeIf { index ->
                        val p = points[index]
                        hypot(p.x * width - down.position.x,
                            (1f - p.y) * height - down.position.y) <= hitRadiusPx
                    }
                    var edited = false
                    if (hit != null) selection = hit
                    else {
                        val inserted = latestCurve.insert(x, y)
                        selection = if (inserted != latestCurve) {
                            live(latestCurves.update(channel, inserted))
                            edited = true
                            inserted.points.indexOfFirst { abs(it.x - x) < .004f }
                        } else -1
                    }
                    down.consume()
                    var lastPosition = down.position
                    do {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (pointer.pressed) {
                            if (selection >= 0 &&
                                (pointer.position - lastPosition).getDistance() >= 1.25f) {
                                lastPosition = pointer.position
                                val currentPoints = latestCurve.points
                                if (selection in currentPoints.indices) {
                                    val next = latestCurve.withPoint(selection,
                                        pointer.position.x / width,
                                        1f - pointer.position.y / height)
                                    if (next != latestCurve) {
                                        live(latestCurves.update(channel, next))
                                        edited = true
                                    }
                                }
                            }
                            pointer.consume()
                        } else {
                            pointer.consume()
                            break
                        }
                    } while (event.changes.any { it.pressed })
                    if (edited) finish()
                }
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
            // Show all four independent channels simultaneously; active channel draws last.
            val renderOrder = PhotoCurveChannel.entries.filter { it != channel } + channel
            renderOrder.forEach { visible ->
                val lookup = PhotoCurveMath.lut(curves.forChannel(visible))
                val path = Path().apply {
                    moveTo(0f, h - lookup[0] / 255f * h)
                    for (i in 1..255) lineTo(i / 255f * w, h - lookup[i] / 255f * h)
                }
                drawPath(path, curveChannelColor(visible).copy(alpha = if (visible == channel) 1f else .48f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = if (visible == channel) 2.8.dp.toPx() else 1.5.dp.toPx(), cap = StrokeCap.Round))
            }
            curve.points.forEachIndexed { index, p ->
                val center = Offset(p.x * w, (1f - p.y) * h)
                drawCircle(Color(0xFF252529), radius = if (selection == index) 11.dp.toPx()
                    else 9.dp.toPx(), center = center)
                drawCircle(curveChannelColor(channel), radius = if (selection == index) 8.dp.toPx()
                    else 6.7.dp.toPx(), center = center)
            }
        }
        Text("Коснись графика для новой точки — веди без отпускания. Существующие точки захватываются рядом с ними.",
            color = Color.LightGray, fontSize = 10.sp)
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

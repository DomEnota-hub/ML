package com.domenota.medialoader.ui.screens

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** The fractional drag accumulator is separate from the whole-unit UI value. */
internal fun geometryRulerDragValue(
    current: Float,
    dragPixels: Float,
    pixelsPerUnit: Float,
    range: ClosedFloatingPointRange<Float>,
): Float {
    require(pixelsPerUnit > 0f)
    return (current - dragPixels / pixelsPerUnit)
        .coerceIn(range.start, range.endInclusive)
}

/**
 * Scrubbable ruler: the yellow hairline is the current value, while the labeled
 * zero tick moves under it. All three geometry modes share the same interaction.
 */
@Composable
internal fun PhotoGeometryRuler(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(onValueChange)
    val latestFinished by rememberUpdatedState(onValueChangeFinished)
    val density = LocalDensity.current
    val pixelsPerDegree = with(density) { 4.dp.toPx() }
    val shortTick = with(density) { 8.dp.toPx() }
    val longTick = with(density) { 18.dp.toPx() }
    val white = Color.White.copy(alpha = .70f)
    val highlight = Color(0xFFFFBD19)

    Canvas(modifier.height(58.dp).pointerInput(valueRange.start, valueRange.endInclusive, pixelsPerDegree) {
        // Keep fractional pointer motion inside this gesture even though the
        // published geometry value is rounded to whole units. Otherwise many
        // small pointer events get lost when each one rounds back to zero.
        var dragValue = latestValue
        var lastPublished = latestValue.roundToInt().toFloat()
        detectHorizontalDragGestures(
            onDragStart = {
                dragValue = latestValue
                lastPublished = latestValue.roundToInt().toFloat()
            },
            onHorizontalDrag = { change, deltaPx ->
                change.consume()
                dragValue = geometryRulerDragValue(
                    dragValue, deltaPx, pixelsPerDegree, valueRange)
                val snapped = dragValue.roundToInt().toFloat()
                if (snapped != lastPublished) {
                    lastPublished = snapped
                    latestChange(snapped)
                }
            },
            onDragEnd = { latestFinished() },
            onDragCancel = { latestFinished() },
        )
    }) {
        val center = size.width / 2f
        val top = size.height * .15f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(255, 189, 25)
            textSize = 12.dp.toPx()
            textAlign = Paint.Align.CENTER
        }
        val start = ceil(valueRange.start.toDouble()).toInt()
        val end = floor(valueRange.endInclusive.toDouble()).toInt()
        for (tick in start..end) {
            val x = center + (tick - value.roundToInt()) * pixelsPerDegree
            if (x < 0f || x > size.width) continue
            val zero = tick == 0
            val tall = zero || tick % 5 == 0
            drawLine(if (zero) highlight else white,
                Offset(x, top), Offset(x, top + if (tall) longTick else shortTick),
                strokeWidth = if (zero) 2.dp.toPx() else 1.dp.toPx())
            if (zero) drawContext.canvas.nativeCanvas.drawText(
                "0", x, size.height - 5.dp.toPx(), paint)
        }
        drawLine(highlight, Offset(center, 0f), Offset(center, top + longTick),
            strokeWidth = 3.dp.toPx())
    }
}

package com.domenota.medialoader.ui.screens.gallery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Horizontal editor slider. NEVER draw any vertical purple marker or divider. */
@Composable
internal fun CutoutBrushSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 8f..100f,
) {
    val callback by rememberUpdatedState(onValueChange)
    val lower = valueRange.start
    val upper = valueRange.endInclusive
    require(lower < upper) { "Slider range must have a positive width" }
    val accent = Color(0xFFFFD34F)
    val neutral = Color(0xFF646464)
    Canvas(
        modifier.fillMaxWidth().height(42.dp)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(value, lower..upper) }
            .pointerInput(lower, upper) {
                detectTapGestures { point ->
                    val v = cutoutSliderTouchFraction(point.x, size.width.toFloat(), 11.dp.toPx())
                    callback(lower + (upper - lower) * v)
                }
            }
            .pointerInput(lower, upper) {
                detectDragGestures(
                    onDragStart = { point ->
                        val v = cutoutSliderTouchFraction(point.x, size.width.toFloat(), 11.dp.toPx())
                        callback(lower + (upper - lower) * v)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val v = cutoutSliderTouchFraction(change.position.x, size.width.toFloat(), 11.dp.toPx())
                        callback(lower + (upper - lower) * v)
                    },
                )
            }
    ) {
        val middle = size.height / 2f
        val thickness = 6.dp.toPx()
        val fraction = ((value - lower) / (upper - lower)).coerceIn(0f, 1f)
        val thumbRadius = 11.dp.toPx().coerceAtMost(size.width / 2f)
        val (trackStart, trackEnd) = cutoutSliderTrackBounds(size.width, thumbRadius)
        val trackWidth = trackEnd - trackStart
        val corner = CornerRadius(thickness / 2f)
        drawRoundRect(neutral, topLeft = Offset(trackStart, middle - thickness / 2f),
            size = Size(trackWidth, thickness), cornerRadius = corner)
        drawRoundRect(accent, topLeft = Offset(trackStart, middle - thickness / 2f),
            size = Size(trackWidth * fraction, thickness), cornerRadius = corner)
        drawCircle(accent, radius = thumbRadius,
            center = Offset(trackStart + trackWidth * fraction, middle))
    }
}

// Keep the entire circular thumb inside the slider's bounds, including at 0% and 100%.
internal fun cutoutSliderTrackBounds(width: Float, radius: Float): Pair<Float, Float> {
    val safeWidth = width.coerceAtLeast(0f)
    val inset = radius.coerceAtLeast(0f).coerceAtMost(safeWidth / 2f)
    return inset to (safeWidth - inset)
}

internal fun cutoutSliderTouchFraction(x: Float, width: Float, radius: Float): Float {
    val (start, end) = cutoutSliderTrackBounds(width, radius)
    return ((x - start) / (end - start).coerceAtLeast(1f)).coerceIn(0f, 1f)
}

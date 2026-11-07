package com.domenota.medialoader.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/** Shared slider style: a round thumb and a thin track, never the Material vertical handle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null, steps: Int = 0,
    accentColor: Color? = null) {
    val color = (accentColor ?: MaterialTheme.colorScheme.primary)
        .copy(alpha = if (enabled) 1f else 0.4f)
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    val zeroColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .95f)
    Slider(value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled,
        valueRange = valueRange, onValueChangeFinished = onValueChangeFinished, steps = steps,
        thumb = { Box(Modifier.size(20.dp).background(color, CircleShape)) },
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                val full = (valueRange.endInclusive - valueRange.start).coerceAtLeast(.0001f)
                val fraction = ((state.value - valueRange.start) / full).coerceIn(0f, 1f)
                drawLine(track, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                    strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                drawLine(color, Offset(0f, size.height / 2),
                    Offset(size.width * fraction, size.height / 2),
                    strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                if (valueRange.start < 0f && valueRange.endInclusive > 0f) {
                    val zeroFraction = -valueRange.start / full
                    val zeroX = zeroFraction * size.width
                    drawLine(zeroColor,
                        Offset(zeroX, 1.dp.toPx()),
                        Offset(zeroX, size.height - 1.dp.toPx()),
                        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        })
}

/** Editor sliders use the existing photo-editor yellow, independent of the app's purple theme. */
internal val editorSliderYellow = Color(0xFFFFBD19)

@Composable
fun EditorSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null, steps: Int = 0) {
    AppSlider(value, onValueChange, modifier, enabled, valueRange,
        onValueChangeFinished, steps, accentColor = editorSliderYellow)
}

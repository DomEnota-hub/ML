package com.domenota.medialoader.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.editor.TextLayer
import com.domenota.medialoader.data.editor.TextLayerRenderer
import com.domenota.medialoader.data.editor.TextLayerTransform
import com.domenota.medialoader.data.editor.PreviewZoom
import kotlin.math.cos
import kotlin.math.sin

/** Preview-only affordances. Export uses the renderer without this overlay. */
@Composable
internal fun TextLayerSelectionOverlay(imageWidth: Int, imageHeight: Int, layer: TextLayer,
    onChange: (TextLayer) -> Unit, zoom: PreviewZoom = PreviewZoom()) {
    val displayScale = zoom.scale
    val context = LocalContext.current
    val current by rememberUpdatedState(layer)
    val change by rememberUpdatedState(onChange)
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val padding = with(density) { 8.dp.toPx() } / displayScale
    val radius = with(density) { 14.dp.toPx() } / displayScale
    val target = with(density) { 24.dp.toPx() } / displayScale
    // Layout includes font, multiline wrapping and actual alignment, as in the export renderer.
    fun frame(value: TextLayer): SelectionFrame {
        val scale = minOf(viewport.width.toFloat() / imageWidth, viewport.height.toFloat() / imageHeight)
        val imageLeft = (viewport.width - imageWidth * scale) / 2f
        val imageTop = (viewport.height - imageHeight * scale) / 2f
        val center = Offset(imageLeft + value.centerX * imageWidth * scale,
            imageTop + value.centerY * imageHeight * scale)
        val layout = TextLayerRenderer.layout(context, imageWidth, imageHeight, value)
        val left = (0 until layout.lineCount).minOf { layout.getLineLeft(it) }
        val right = (0 until layout.lineCount).maxOf { layout.getLineRight(it) }
        val border = padding + layout.paint.textSize * value.outline * scale
        val bounds = Rect(center.x + (left - layout.width / 2f) * scale - border,
            center.y - layout.height / 2f * scale - border,
            center.x + (right - layout.width / 2f) * scale + border,
            center.y + layout.height / 2f * scale + border)
        val corner = center + (bounds.bottomRight - center).rotated(value.rotation)
        val placed = zoom.handle(center.x, center.y, corner.x, corner.y,
            viewport.width.toFloat(), viewport.height.toFloat(), radius, target + radius)
        val handle = Offset(placed.first, placed.second)
        return SelectionFrame(center, bounds, corner, handle, scale)
    }
    val visibleFrame = remember(layer, viewport, density, zoom) { frame(layer) }
    Canvas(Modifier.fillMaxSize().onSizeChanged { viewport = it }
        .semantics {
            contentDescription = "Рамка надписи: перемещение, размер и поворот"
            customActions = listOf(
                CustomAccessibilityAction("Увеличить текст") { change(current.copy(size = (current.size * 1.1f).coerceAtMost(0.25f))); true },
                CustomAccessibilityAction("Уменьшить текст") { change(current.copy(size = (current.size / 1.1f).coerceAtLeast(0.02f))); true },
                CustomAccessibilityAction("Сбросить поворот") { change(current.copy(rotation = 0f)); true })
        }
        .pointerInput(layer.id, viewport, zoom) {
            if (viewport.width == 0 || viewport.height == 0) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                val start = current
                val initial = frame(start)
                val resizing = (down.position - initial.handle).getDistance() <= target
                val local = initial.center + (down.position - initial.center).rotated(-start.rotation)
                if (!resizing && !initial.bounds.contains(local)) return@awaitEachGesture
                down.consume()
                val startVector = down.position - initial.center
                do {
                    val event = awaitPointerEvent()
                    val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (pointer.isConsumed) break
                    if (!pointer.pressed) { pointer.consume(); break }
                    val offset = pointer.position - down.position
                    if (resizing) {
                        val vector = pointer.position - initial.center
                        change(TextLayerTransform.fromHandle(start, startVector.x, startVector.y, vector.x, vector.y, current))
                    } else {
                        change(start.copy(centerX = (start.centerX + offset.x / (imageWidth * initial.scale)).coerceIn(0f, 1f),
                            centerY = (start.centerY + offset.y / (imageHeight * initial.scale)).coerceIn(0f, 1f)))
                    }
                    pointer.consume()
                } while (event.changes.any { it.pressed })
            }
        }) {
        if (viewport.width == 0) return@Canvas
        val f = visibleFrame
        withTransform({ rotate(layer.rotation, f.center) }) {
            drawRect(Color.White, f.bounds.topLeft, f.bounds.size, style = Stroke(1.5.dp.toPx() / displayScale))
        }
        if ((f.corner - f.handle).getDistance() > 1f) {
            drawLine(Color.White, f.corner, f.handle, 1.dp.toPx() / displayScale)
        }
        drawCircle(Color.White, radius, f.handle)
        val a = f.handle - Offset(radius * 0.38f, radius * 0.38f)
        val b = f.handle + Offset(radius * 0.38f, radius * 0.38f)
        val stroke = 2.dp.toPx() / displayScale
        drawLine(Color.DarkGray, a, b, stroke, StrokeCap.Round)
        val tip = radius * 0.4f
        drawLine(Color.DarkGray, a, a + Offset(tip, 0f), stroke, StrokeCap.Round)
        drawLine(Color.DarkGray, a, a + Offset(0f, tip), stroke, StrokeCap.Round)
        drawLine(Color.DarkGray, b, b - Offset(tip, 0f), stroke, StrokeCap.Round)
        drawLine(Color.DarkGray, b, b - Offset(0f, tip), stroke, StrokeCap.Round)
    }
}

private data class SelectionFrame(val center: Offset, val bounds: Rect, val corner: Offset,
    val handle: Offset, val scale: Float)

private fun Offset.rotated(degrees: Float): Offset {
    val angle = Math.toRadians(degrees.toDouble())
    return Offset((x * cos(angle) - y * sin(angle)).toFloat(), (x * sin(angle) + y * cos(angle)).toFloat())
}

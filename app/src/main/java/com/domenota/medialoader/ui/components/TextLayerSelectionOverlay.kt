package com.domenota.medialoader.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.nativeCanvas
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
import com.domenota.medialoader.data.editor.TextLayerFitting
import com.domenota.medialoader.data.editor.TextLayerRenderer
import com.domenota.medialoader.data.editor.TextLayerTransform
import com.domenota.medialoader.data.editor.PreviewZoom
import kotlin.math.cos
import kotlin.math.sin

/** Preview-only affordances. Export uses the renderer without this overlay. */
@Composable
internal fun TextLayerSelectionOverlay(imageWidth: Int, imageHeight: Int, layer: TextLayer,
    onChange: (TextLayer) -> Unit, zoom: PreviewZoom = PreviewZoom(),
    onTap: () -> Unit = {}, onDuplicate: () -> Unit = {}, onDelete: () -> Unit = {},
    cornerActions: Boolean = false, fitInside: Boolean = false, onGestureStart: () -> Unit = {}, onGestureEnd: () -> Unit = {}) {
    val displayScale = zoom.scale
    val context = LocalContext.current
    val current by rememberUpdatedState(layer)
    val change by rememberUpdatedState(onChange)
    val tapped by rememberUpdatedState(onTap)
    val duplicate by rememberUpdatedState(onDuplicate)
    val delete by rememberUpdatedState(onDelete)
    val started by rememberUpdatedState(onGestureStart)
    val finished by rememberUpdatedState(onGestureEnd)
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val padding = with(density) { 8.dp.toPx() } / displayScale
    val radius = with(density) { 12.dp.toPx() } / displayScale
    val target = with(density) { 24.dp.toPx() } / displayScale
    // Layout includes font, multiline wrapping and actual alignment, as in the export renderer.
    fun frame(value: TextLayer): SelectionFrame {
        val scale = minOf(viewport.width.toFloat() / imageWidth, viewport.height.toFloat() / imageHeight)
        val imageLeft = (viewport.width - imageWidth * scale) / 2f
        val imageTop = (viewport.height - imageHeight * scale) / 2f
        val measurement = if (value.text.isBlank()) value.copy(text = "Ваш текст") else value
        val placement = if (fitInside) TextLayerFitting.placement(context,imageWidth,imageHeight,measurement) else null
        val textScale = scale * (placement?.scale ?: 1f)
        val center = Offset(imageLeft + (placement?.centerX ?: value.centerX * imageWidth) * scale,
            imageTop + (placement?.centerY ?: value.centerY * imageHeight) * scale)
        // An empty layer is still selectable and movable. The placeholder
        // is preview-only and NEVER stored in the layer or exported.
        val layout = TextLayerRenderer.layout(context, imageWidth, imageHeight, measurement)
        val left = (0 until layout.lineCount).minOf { layout.getLineLeft(it) }
        val right = (0 until layout.lineCount).maxOf { layout.getLineRight(it) }
        val border = padding + layout.paint.textSize * value.outline * textScale
        val bounds = Rect(center.x + (left - layout.width / 2f) * textScale - border,
            center.y - layout.height / 2f * textScale - border,
            center.x + (right - layout.width / 2f) * textScale + border,
            center.y + layout.height / 2f * textScale + border)
        val corner = center + (bounds.bottomRight - center).rotated(value.rotation)
        val placed = zoom.handle(center.x, center.y, corner.x, corner.y,
            viewport.width.toFloat(), viewport.height.toFloat(), radius, target + radius)
        val handle = Offset(placed.first, placed.second)
        return SelectionFrame(center, bounds, corner, handle, scale)
    }
    fun actionPositions(f: SelectionFrame, rotation: Float): Pair<Offset, Offset> {
        if (!cornerActions) return Offset(f.bounds.left, f.bounds.top - radius * 1.7f) to
            Offset(f.bounds.right, f.bounds.top - radius * 1.7f)
        fun safe(point: Offset) = Offset(point.x.coerceIn(radius, maxOf(radius, viewport.width - radius)),
            point.y.coerceIn(radius, maxOf(radius, viewport.height - radius)))
        return safe(f.center + (f.bounds.topLeft - f.center).rotated(rotation)) to
            safe(f.center + (f.bounds.topRight - f.center).rotated(rotation))
    }
    val copyPaint = remember(radius) { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.DKGRAY; textSize = radius * .95f
        textAlign = android.graphics.Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
    } }
    val visibleFrame = remember(layer, viewport, density, zoom, fitInside) { frame(layer) }
    Canvas(Modifier.fillMaxSize().then(if (cornerActions) Modifier.clipToBounds() else Modifier).onSizeChanged { viewport = it }
        .semantics {
            contentDescription = "Рамка надписи: перемещение, размер и поворот"
            customActions = listOf(
                CustomAccessibilityAction("Увеличить текст") { change(current.copy(size = (current.size * 1.1f).coerceAtMost(0.25f))); true },
                CustomAccessibilityAction("Уменьшить текст") { change(current.copy(size = (current.size / 1.1f).coerceAtLeast(0.02f))); true },
                CustomAccessibilityAction("Сбросить поворот") { change(current.copy(rotation = 0f)); true },
                CustomAccessibilityAction("Копировать надпись") { duplicate(); true },
                CustomAccessibilityAction("Удалить надпись") { delete(); true })
        }
        .pointerInput(layer.id, viewport, zoom) {
            if (viewport.width == 0 || viewport.height == 0) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                val start = if (fitInside) TextLayerFitting.centered(context,imageWidth,imageHeight,current) else current
                val initial = frame(start)
                val (copyTarget, deleteTarget) = actionPositions(initial, start.rotation)
                if ((down.position - copyTarget).getDistance() <= target) {
                    down.consume(); duplicate(); return@awaitEachGesture
                }
                if ((down.position - deleteTarget).getDistance() <= target) {
                    down.consume(); delete(); return@awaitEachGesture
                }
                val resizing = (down.position - initial.handle).getDistance() <= target
                val local = initial.center + (down.position - initial.center).rotated(-start.rotation)
                if (!resizing && !initial.bounds.contains(local)) return@awaitEachGesture
                down.consume()
                val startVector = down.position - initial.center
                var dragged = false
                started()
                try { do {
                    val event = awaitPointerEvent()
                    val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (pointer.isConsumed) break
                    if (!pointer.pressed) {
                        if (!dragged) tapped()
                        pointer.consume()
                        break
                    }
                    val offset = pointer.position - down.position
                    if (offset.getDistance() > viewConfiguration.touchSlop) dragged = true
                    if (resizing) {
                        val vector = pointer.position - initial.center
                        change(TextLayerTransform.fromHandle(start, startVector.x, startVector.y, vector.x, vector.y, current))
                    } else {
                        change(start.copy(centerX = (start.centerX + offset.x / (imageWidth * initial.scale)).coerceIn(0f, 1f),
                            centerY = (start.centerY + offset.y / (imageHeight * initial.scale)).coerceIn(0f, 1f)))
                    }
                    pointer.consume()
                } while (event.changes.any { it.pressed }) } finally { finished() }
            }
        }) {
        if (viewport.width == 0) return@Canvas
        val f = visibleFrame
        if (layer.text.isBlank()) {
            val native = drawContext.canvas.nativeCanvas
            val saved = native.save()
            try {
                native.translate(
                    (viewport.width - imageWidth * f.scale) / 2f,
                    (viewport.height - imageHeight * f.scale) / 2f,
                )
                native.scale(f.scale, f.scale)
                TextLayerRenderer.draw(
                    context, native, imageWidth, imageHeight,
                    listOf(layer.copy(text = "Ваш текст", opacity = .60f)),
                    includeAll = true, fitInside = fitInside,
                )
            } finally {
                native.restoreToCount(saved)
            }
        }
        val (copyTarget, deleteTarget) = actionPositions(f, layer.rotation)
        withTransform({ rotate(layer.rotation, f.center) }) {
            drawRect(Color.White, f.bounds.topLeft, f.bounds.size, style = Stroke(1.5.dp.toPx() / displayScale))
        }
        listOf(copyTarget, deleteTarget).forEach { circle ->
            drawCircle(Color.White, radius * .86f, circle)
        }
        if (cornerActions) {
            drawContext.canvas.nativeCanvas.drawText("+1", copyTarget.x,
                copyTarget.y - (copyPaint.ascent() + copyPaint.descent()) / 2f, copyPaint)
        } else {
        drawLine(Color.DarkGray, copyTarget - Offset(radius * .32f, 0f),
            copyTarget + Offset(radius * .32f, 0f), strokeWidth = 1.7.dp.toPx())
        drawLine(Color.DarkGray, copyTarget - Offset(0f, radius * .32f),
            copyTarget + Offset(0f, radius * .32f), strokeWidth = 1.7.dp.toPx())
        }
        drawLine(Color.DarkGray, deleteTarget - Offset(radius * .25f, radius * .25f),
            deleteTarget + Offset(radius * .25f, radius * .25f), strokeWidth = 1.7.dp.toPx())
        drawLine(Color.DarkGray, deleteTarget - Offset(radius * .25f, -radius * .25f),
            deleteTarget + Offset(radius * .25f, -radius * .25f), strokeWidth = 1.7.dp.toPx())
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

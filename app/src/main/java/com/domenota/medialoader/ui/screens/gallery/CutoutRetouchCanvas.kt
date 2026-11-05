package com.domenota.medialoader.ui.screens.gallery

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.min

private data class MaskPreviewTransform(val scale: Float, val left: Float, val top: Float) {
    fun bitmapPoint(point: Offset): Offset =
        Offset((point.x - left) / scale, (point.y - top) / scale)
}

private fun previewTransform(canvasWidth: Float, canvasHeight: Float, imageWidth: Int, imageHeight: Int): MaskPreviewTransform {
    val scale = min(canvasWidth / imageWidth, canvasHeight / imageHeight).coerceAtLeast(.0001f)
    return MaskPreviewTransform(scale, (canvasWidth - imageWidth * scale) / 2f, (canvasHeight - imageHeight * scale) / 2f)
}

/**
 * Draw directly from the mutable preview and invalidate on each pointer update.
 * Map gestures through ContentScale.Fit, not through the whole screen, otherwise
 * editing would shift on tall/landscape images.
 */
@Composable
internal fun CutoutRetouchCanvas(
    editor: CutoutMaskEditor, restore: Boolean, radius: Float, onStrokeDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var canvasSize by remember(editor) { mutableStateOf(IntSize.Zero) }
    var version by remember(editor) { mutableIntStateOf(0) }
    var cursor by remember(editor) { mutableStateOf<Offset?>(null) }
    val filterPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG) }

    Canvas(modifier
        .onSizeChanged { canvasSize = it }
        .pointerInput(editor, restore, radius, canvasSize) {
            if (canvasSize.width < 1 || canvasSize.height < 1) return@pointerInput
            val bounds = previewTransform(canvasSize.width.toFloat(), canvasSize.height.toFloat(), editor.width, editor.height)
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val start = bounds.bitmapPoint(down.position)
                if (start.x < 0f || start.y < 0f || start.x >= editor.width || start.y >= editor.height) {
                    waitForUpOrCancellation()
                    return@awaitEachGesture
                }
                editor.startStroke(start.x, start.y, radius, restore)
                cursor = down.position
                version++
                var prev = start
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val next = bounds.bitmapPoint(change.position).let {
                            Offset(it.x.coerceIn(0f, editor.width - 1f), it.y.coerceIn(0f, editor.height - 1f))
                        }
                        editor.dragStroke(prev.x, prev.y, next.x, next.y, radius, restore)
                        prev = next
                        cursor = change.position
                        change.consume()
                        version++
                    }
                } finally {
                    editor.endStroke()
                    cursor = null
                    version++
                    onStrokeDone()
                }
            }
        }
    ) {
        @Suppress("UNUSED_VARIABLE")
        val paintInvalidationKey = version
        val t = previewTransform(size.width, size.height, editor.width, editor.height)
        drawIntoCanvas {
            it.nativeCanvas.drawBitmap(editor.preview, null,
                RectF(t.left, t.top, t.left + editor.width * t.scale, t.top + editor.height * t.scale),
                filterPaint)
        }
        cursor?.let { point ->
            drawCircle(Color(0xFFFFD54F), radius * t.scale, point, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6f))
        }
    }
}

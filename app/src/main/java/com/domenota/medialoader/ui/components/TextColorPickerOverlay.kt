package com.domenota.medialoader.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.editor.ImageColorSample
import com.domenota.medialoader.data.editor.ImagePixel
import com.domenota.medialoader.data.editor.PreviewZoom

/** Inspect source pixels while dragging; only releasing on the image commits a color. */
@Composable
internal fun TextColorPickerOverlay(image: Bitmap, onInspect: (Int?) -> Unit, onPick: (Int) -> Unit,
    zoom: PreviewZoom = PreviewZoom()) {
    var pixel by remember(image, zoom) { mutableStateOf<ImagePixel?>(null) }
    val inspect by rememberUpdatedState(onInspect)
    val pick by rememberUpdatedState(onPick)
    val bitmap = remember(image) { image.asImageBitmap() }
    Canvas(Modifier.fillMaxSize().pointerInput(image, zoom) {
        fun at(position: Offset): ImagePixel? {
            val point = zoom.toContent(position.x, position.y, size.width.toFloat(), size.height.toFloat())
            return ImageColorSample.pixel(image.width, image.height,
                size.width.toFloat(), size.height.toFloat(), point.first, point.second)
        }
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true)
            val start = at(down.position) ?: return@awaitEachGesture
            pixel = start
            inspect(image.getPixel(start.x, start.y))
            down.consume()
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (pointer.isConsumed) break
                    val released = pointer.changedToUp()
                    pixel = at(pointer.position)
                    inspect(pixel?.let { image.getPixel(it.x, it.y) })
                    pointer.consume()
                    if (!pointer.pressed) {
                        if (released) pixel?.let { pick(image.getPixel(it.x, it.y)) }
                        break
                    }
                }
            } finally { pixel = null; inspect(null) }
        }
    }) {
        val point = pixel ?: return@Canvas
        val scale = minOf(size.width / image.width, size.height / image.height)
        val base = Offset((size.width - image.width * scale) / 2f + (point.x + 0.5f) * scale,
            (size.height - image.height * scale) / 2f + (point.y + 0.5f) * scale)
        val center = Offset(size.width / 2f + (base.x - size.width / 2f) * zoom.scale + zoom.x,
            size.height / 2f + (base.y - size.height / 2f) * zoom.scale + zoom.y)
        drawCircle(Color.Black, 9.dp.toPx(), center, style = Stroke(3.dp.toPx()))
        drawCircle(Color.White, 9.dp.toPx(), center, style = Stroke(1.dp.toPx()))
        val side = minOf(96.dp.toPx(), size.height - 12.dp.toPx(), size.width / 2f).coerceAtLeast(1f).toInt()
        val margin = 6.dp.toPx().toInt()
        val left = if (center.x > size.width / 2f) margin else size.width.toInt() - side - margin
        val top = margin
        val sw = minOf(11, image.width); val sh = minOf(11, image.height)
        val sx = (point.x - sw / 2).coerceIn(0, image.width - sw)
        val sy = (point.y - sh / 2).coerceIn(0, image.height - sh)
        drawImage(bitmap, srcOffset = IntOffset(sx, sy), srcSize = IntSize(sw, sh),
            dstOffset = IntOffset(left, top), dstSize = IntSize(side, side), filterQuality = FilterQuality.None)
        drawRect(Color.White, Offset(left.toFloat(), top.toFloat()),
            androidx.compose.ui.geometry.Size(side.toFloat(), side.toFloat()), style = Stroke(2.dp.toPx()))
        val marker = Offset(left + (point.x - sx + 0.5f) * side / sw, top + (point.y - sy + 0.5f) * side / sh)
        drawCircle(Color.Black, 5.dp.toPx(), marker, style = Stroke(3.dp.toPx()))
        drawCircle(Color.White, 5.dp.toPx(), marker, style = Stroke(1.dp.toPx()))
    }
}

package com.domenota.medialoader.ui.screens.gallery

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.min

/** Screen/image coordinates shared by the brush, ghost preview and magnifier. */
internal data class CutoutImageTransform(val scale: Float, val left: Float, val top: Float) {
    fun toImage(pointer: Offset): Offset =
        Offset((pointer.x - left) / scale, (pointer.y - top) / scale)
    fun toScreen(image: Offset): Offset =
        Offset(image.x * scale + left, image.y * scale + top)
}

internal data class CutoutViewport(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 6f
    }

    fun transform(view: IntSize, image: IntSize): CutoutImageTransform {
        val width = view.width.coerceAtLeast(1).toFloat()
        val height = view.height.coerceAtLeast(1).toFloat()
        val base = min(width / image.width.coerceAtLeast(1),
            height / image.height.coerceAtLeast(1)).coerceAtLeast(.000001f)
        val scale = base * zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        return CutoutImageTransform(scale,
            (width - image.width * scale) / 2f + panX,
            (height - image.height * scale) / 2f + panY)
    }

    /**
     * Preserves the image location beneath the fingers while zooming and
     * restricts pan so that the zoomed content cannot disappear off-screen.
     */
    fun gesture(view: IntSize, image: IntSize, center: Offset, pan: Offset, zoomFactor: Float): CutoutViewport {
        val currentTransform = transform(view, image)
        val focal = currentTransform.toImage(center - pan)
        val newZoom = (zoom * zoomFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val base = CutoutViewport(newZoom).transform(view, image)
        val nextPanX = center.x - base.left - focal.x * base.scale
        val nextPanY = center.y - base.top - focal.y * base.scale
        val maxX = max(0f, (image.width * base.scale - view.width) / 2f)
        val maxY = max(0f, (image.height * base.scale - view.height) / 2f)
        return CutoutViewport(newZoom, nextPanX.coerceIn(-maxX, maxX), nextPanY.coerceIn(-maxY, maxY))
    }
}

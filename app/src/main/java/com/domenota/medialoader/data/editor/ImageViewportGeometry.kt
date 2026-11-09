package com.domenota.medialoader.data.editor

import kotlin.math.hypot

/** A touch position in the unscaled preview viewport, in pixels. */
internal data class PreviewPoint(val x: Float, val y: Float)

/** A magnifier rectangle entirely inside the image preview, in pixels. */
internal data class MagnifierBounds(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/**
 * The preview uses ContentScale.FillBounds and a centered graphicsLayer scale + pan.
 * Both touch-to-image and image-to-preview MUST share this exact transform.
 * Reject touches outside the original image instead of silently painting its edge.
 */
internal object ImageViewportGeometry {
    fun viewportToImage(
        x: Float, y: Float, width: Float, height: Float,
        zoom: Float, panX: Float, panY: Float,
    ): MaskPoint? {
        if (!valid(width, height, zoom, panX, panY) ||
            !x.isFinite() || !y.isFinite()) return null
        val sourceX = (x - panX - width / 2f) / zoom + width / 2f
        val sourceY = (y - panY - height / 2f) / zoom + height / 2f
        val nx = sourceX / width
        val ny = sourceY / height
        if (!nx.isFinite() || !ny.isFinite() ||
            nx !in 0f..1f || ny !in 0f..1f) return null
        return MaskPoint(nx, ny)
    }

    fun imageToViewport(
        point: MaskPoint, width: Float, height: Float,
        zoom: Float, panX: Float, panY: Float,
    ): PreviewPoint? {
        if (!valid(width, height, zoom, panX, panY)) return null
        val x = (point.x - .5f) * width * zoom + width / 2f + panX
        val y = (point.y - .5f) * height * zoom + height / 2f + panY
        return if (x.isFinite() && y.isFinite()) PreviewPoint(x, y) else null
    }

    /**
     * Prefer the left side, on the opposite vertical end from the finger.
     * If a short landscape viewport makes that impossible, try the right side.
     * Return null instead of obscuring the finger on exceptionally small previews.
     */
    fun placeMagnifier(
        finger: PreviewPoint,
        viewportWidth: Float,
        viewportHeight: Float,
        maxWidth: Float,
        inset: Float,
        clearance: Float,
    ): MagnifierBounds? {
        if (!listOf(finger.x, finger.y, viewportWidth, viewportHeight,
                maxWidth, inset, clearance).all { it.isFinite() } ||
            viewportWidth <= 0f || viewportHeight <= 0f ||
            maxWidth <= 0f || inset < 0f || clearance < 0f ||
            finger.x !in 0f..viewportWidth || finger.y !in 0f..viewportHeight) return null

        val availableWidth = viewportWidth - inset * 2f
        val availableHeight = viewportHeight - inset * 2f
        if (availableWidth <= 0f || availableHeight <= 0f) return null
        val lensWidth = minOf(maxWidth, viewportWidth * .43f,
            availableWidth, availableHeight / .72f)
        if (lensWidth < 16f) return null
        val lensHeight = lensWidth * .72f
        val top = inset
        val bottom = viewportHeight - inset - lensHeight
        val left = inset
        val right = viewportWidth - inset - lensWidth

        fun separation(rect: MagnifierBounds): Float {
            val dx = maxOf(rect.left - finger.x, finger.x - rect.right, 0f)
            val dy = maxOf(rect.top - finger.y, finger.y - rect.bottom, 0f)
            return hypot(dx, dy)
        }

        for (x in listOf(left, right)) {
            val candidates = listOf(
                MagnifierBounds(x, top, lensWidth, lensHeight),
                MagnifierBounds(x, bottom, lensWidth, lensHeight),
            )
            val safe = candidates.filter { separation(it) >= clearance }
            if (safe.isNotEmpty()) return safe.maxByOrNull { separation(it) }
        }
        return null
    }

    private fun valid(width: Float, height: Float, zoom: Float,
                      panX: Float, panY: Float): Boolean =
        width.isFinite() && height.isFinite() && zoom.isFinite() &&
        panX.isFinite() && panY.isFinite() &&
        width > 0f && height > 0f && zoom > 0f
}

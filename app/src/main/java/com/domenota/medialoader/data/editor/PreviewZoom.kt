package com.domenota.medialoader.data.editor

/** Screen-space translation around the center of a preview; never part of export geometry. */
internal data class PreviewZoom(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    fun transform(change: Float, panX: Float, panY: Float, pivotX: Float, pivotY: Float,
        width: Float, height: Float, imageWidth: Int = 0, imageHeight: Int = 0): PreviewZoom {
        if (width <= 0f || height <= 0f || change <= 0f ||
            !listOf(change, panX, panY, pivotX, pivotY, width, height).all { it.isFinite() }) return this
        val next = (scale * change).coerceIn(1f, 6f)
        if (next == 1f) return PreviewZoom()
        val ratio = next / scale
        val px = pivotX - width / 2f
        val py = pivotY - height / 2f
        val fit = if (imageWidth > 0 && imageHeight > 0) minOf(width / imageWidth, height / imageHeight) else 1f
        val contentWidth = if (imageWidth > 0 && imageHeight > 0) imageWidth * fit else width
        val contentHeight = if (imageWidth > 0 && imageHeight > 0) imageHeight * fit else height
        val limitX = ((contentWidth * next - width) / 2f).coerceAtLeast(0f)
        val limitY = ((contentHeight * next - height) / 2f).coerceAtLeast(0f)
        return PreviewZoom(next, ((x - px) * ratio + px + panX).coerceIn(-limitX, limitX),
            ((y - py) * ratio + py + panY).coerceIn(-limitY, limitY))
    }
    fun toContent(screenX: Float, screenY: Float, width: Float, height: Float): Pair<Float, Float> =
        (width / 2f + (screenX - width / 2f - x) / scale) to
            (height / 2f + (screenY - height / 2f - y) / scale)

    fun handle(cx: Float, cy: Float, cornerX: Float, cornerY: Float, width: Float, height: Float,
        radius: Float, minimumDistance: Float): Pair<Float, Float> {
        val origin = toContent(0f, 0f, width, height)
        val point = TextHandlePlacement.place(cx - origin.first, cy - origin.second,
            cornerX - origin.first, cornerY - origin.second, width / scale, height / scale,
            radius, minimumDistance)
        return point.first + origin.first to point.second + origin.second
    }
}

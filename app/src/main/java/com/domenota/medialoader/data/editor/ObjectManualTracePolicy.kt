package com.domenota.medialoader.data.editor

/**
 * The OBJECTS/hand switch is a thin source-space path, not the adjustable
 * brush in the separate MANUAL mode. The path is an actual LaMa inpainting
 * mask: no MobileSAM inference or pretend object detection is involved.
 *
 * For a larger object the user can mark several paths before pressing Start.
 * Fixed diameter is measured in working-image pixels, not viewport pixels,
 * so zoom and pan never change the area sent to LaMa.
 */
internal object ObjectManualTracePolicy {
    private const val TRACE_DIAMETER_PX = 7f

    fun toStroke(points: List<MaskPoint>, imageWidth: Int, imageHeight: Int): MaskStroke? {
        require(imageWidth > 0 && imageHeight > 0) { "Invalid working image dimensions" }
        if (points.isEmpty()) return null
        val diameter = (TRACE_DIAMETER_PX / minOf(imageWidth, imageHeight))
            .coerceIn(0.001f, 0.5f)
        return MaskStroke(points.toList(), diameter, erase = false)
    }
}

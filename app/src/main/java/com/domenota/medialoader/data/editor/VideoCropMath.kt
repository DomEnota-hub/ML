package com.domenota.medialoader.data.editor

import kotlin.math.max
import kotlin.math.min

enum class VideoCropEdge { LEFT, RIGHT, TOP, BOTTOM }

enum class VideoCropHandle {
    LEFT,
    RIGHT,
    TOP,
    BOTTOM,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}

enum class CropAspectPreset(val label: String) {
    FREE("Свободно"),
    ORIGINAL("Исходное"),
    RATIO_16_9("16:9"),
    RATIO_9_16("9:16"),
    RATIO_4_3("4:3"),
    RATIO_3_4("3:4"),
    RATIO_1_1("1:1");

    fun targetRatio(sourceWidth: Int, sourceHeight: Int): Float? = when (this) {
        FREE -> null
        ORIGINAL -> sourceWidth.toFloat() / sourceHeight.coerceAtLeast(1)
        RATIO_16_9 -> 16f / 9f
        RATIO_9_16 -> 9f / 16f
        RATIO_4_3 -> 4f / 3f
        RATIO_3_4 -> 3f / 4f
        RATIO_1_1 -> 1f
    }
}

data class CropFractions(
    val left: Float = 0f,
    val right: Float = 0f,
    val top: Float = 0f,
    val bottom: Float = 0f,
) {
    val keptWidth: Float get() = 1f - left - right
    val keptHeight: Float get() = 1f - top - bottom
}

object VideoCropMath {
    private const val MIN_RETAINED = 0.05f
    private const val MAX_EDGE = 0.95f

    fun centerCrop(
        sourceWidth: Int,
        sourceHeight: Int,
        preset: CropAspectPreset,
    ): CropFractions {
        require(sourceWidth > 0 && sourceHeight > 0)
        val target = preset.targetRatio(sourceWidth, sourceHeight) ?: return CropFractions()
        val source = sourceWidth.toFloat() / sourceHeight
        if (kotlin.math.abs(source - target) < 0.0001f) return CropFractions()

        return if (source > target) {
            val keptWidth = (target / source).coerceIn(MIN_RETAINED, 1f)
            val side = (1f - keptWidth) / 2f
            CropFractions(left = side, right = side)
        } else {
            val keptHeight = (source / target).coerceIn(MIN_RETAINED, 1f)
            val side = (1f - keptHeight) / 2f
            CropFractions(top = side, bottom = side)
        }
    }

    fun changeEdge(
        current: CropFractions,
        edge: VideoCropEdge,
        requestedCrop: Float,
        sourceWidth: Int,
        sourceHeight: Int,
        preset: CropAspectPreset,
    ): CropFractions {
        require(sourceWidth > 0 && sourceHeight > 0)
        val requested = requestedCrop.coerceIn(0f, MAX_EDGE)
        val target = preset.targetRatio(sourceWidth, sourceHeight)

        if (target == null) {
            return freeEdge(current, edge, requested)
        }

        return when (edge) {
            VideoCropEdge.LEFT, VideoCropEdge.RIGHT ->
                changeVerticalEdge(current, edge, requested, sourceWidth, sourceHeight, target)
            VideoCropEdge.TOP, VideoCropEdge.BOTTOM ->
                changeHorizontalEdge(current, edge, requested, sourceWidth, sourceHeight, target)
        }
    }

    fun changeHandle(
        current: CropFractions,
        handle: VideoCropHandle,
        deltaXFraction: Float,
        deltaYFraction: Float,
        sourceWidth: Int,
        sourceHeight: Int,
        preset: CropAspectPreset,
    ): CropFractions {
        require(sourceWidth > 0 && sourceHeight > 0)
        return when (handle) {
            VideoCropHandle.LEFT -> changeEdge(
                current,
                VideoCropEdge.LEFT,
                current.left + deltaXFraction,
                sourceWidth,
                sourceHeight,
                preset,
            )
            VideoCropHandle.RIGHT -> changeEdge(
                current,
                VideoCropEdge.RIGHT,
                current.right - deltaXFraction,
                sourceWidth,
                sourceHeight,
                preset,
            )
            VideoCropHandle.TOP -> changeEdge(
                current,
                VideoCropEdge.TOP,
                current.top + deltaYFraction,
                sourceWidth,
                sourceHeight,
                preset,
            )
            VideoCropHandle.BOTTOM -> changeEdge(
                current,
                VideoCropEdge.BOTTOM,
                current.bottom - deltaYFraction,
                sourceWidth,
                sourceHeight,
                preset,
            )
            VideoCropHandle.TOP_LEFT,
            VideoCropHandle.TOP_RIGHT,
            VideoCropHandle.BOTTOM_LEFT,
            VideoCropHandle.BOTTOM_RIGHT -> changeCorner(
                current = current,
                handle = handle,
                deltaXFraction = deltaXFraction,
                deltaYFraction = deltaYFraction,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                preset = preset,
            )
        }
    }

    private fun changeCorner(
        current: CropFractions,
        handle: VideoCropHandle,
        deltaXFraction: Float,
        deltaYFraction: Float,
        sourceWidth: Int,
        sourceHeight: Int,
        preset: CropAspectPreset,
    ): CropFractions {
        val leftSide = handle == VideoCropHandle.TOP_LEFT ||
            handle == VideoCropHandle.BOTTOM_LEFT
        val topSide = handle == VideoCropHandle.TOP_LEFT ||
            handle == VideoCropHandle.TOP_RIGHT

        val currentX = if (leftSide) current.left else 1f - current.right
        val currentY = if (topSide) current.top else 1f - current.bottom
        val requestedX = (currentX + deltaXFraction).coerceIn(0f, 1f)
        val requestedY = (currentY + deltaYFraction).coerceIn(0f, 1f)

        val anchorX = if (leftSide) 1f - current.right else current.left
        val anchorY = if (topSide) 1f - current.bottom else current.top

        val targetRatio = preset.targetRatio(sourceWidth, sourceHeight)
        if (targetRatio == null) {
            val nextLeft = if (leftSide) {
                requestedX.coerceAtMost(anchorX - MIN_RETAINED)
            } else {
                anchorX
            }
            val nextRightEdge = if (leftSide) {
                anchorX
            } else {
                requestedX.coerceAtLeast(anchorX + MIN_RETAINED)
            }
            val nextTop = if (topSide) {
                requestedY.coerceAtMost(anchorY - MIN_RETAINED)
            } else {
                anchorY
            }
            val nextBottomEdge = if (topSide) {
                anchorY
            } else {
                requestedY.coerceAtLeast(anchorY + MIN_RETAINED)
            }
            return CropFractions(
                left = nextLeft,
                right = 1f - nextRightEdge,
                top = nextTop,
                bottom = 1f - nextBottomEdge,
            ).sanitized()
        }

        val widthPerHeight =
            targetRatio * sourceHeight.toFloat() / sourceWidth.coerceAtLeast(1)
        val desiredDx = kotlin.math.abs(requestedX - anchorX)
        val desiredDy = kotlin.math.abs(requestedY - anchorY)
        val projectedHeight =
            ((widthPerHeight * desiredDx) + desiredDy) /
                ((widthPerHeight * widthPerHeight) + 1f)

        val maxDx = if (leftSide) anchorX else 1f - anchorX
        val maxDy = if (topSide) anchorY else 1f - anchorY
        val maxHeight = min(maxDy, maxDx / widthPerHeight.coerceAtLeast(0.0001f))
        val minHeight = max(
            MIN_RETAINED,
            MIN_RETAINED / widthPerHeight.coerceAtLeast(0.0001f),
        ).coerceAtMost(maxHeight)
        val keptHeight = projectedHeight.coerceIn(minHeight, maxHeight)
        val keptWidth = widthPerHeight * keptHeight

        val left = if (leftSide) anchorX - keptWidth else anchorX
        val rightEdge = if (leftSide) anchorX else anchorX + keptWidth
        val top = if (topSide) anchorY - keptHeight else anchorY
        val bottomEdge = if (topSide) anchorY else anchorY + keptHeight
        return CropFractions(
            left = left,
            right = 1f - rightEdge,
            top = top,
            bottom = 1f - bottomEdge,
        ).sanitized()
    }

    fun fromPixels(
        sourceWidth: Int,
        sourceHeight: Int,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
    ): CropFractions {
        require(sourceWidth > 0 && sourceHeight > 0)
        return CropFractions(
            left = left.coerceAtLeast(0).toFloat() / sourceWidth,
            right = right.coerceAtLeast(0).toFloat() / sourceWidth,
            top = top.coerceAtLeast(0).toFloat() / sourceHeight,
            bottom = bottom.coerceAtLeast(0).toFloat() / sourceHeight,
        ).sanitized()
    }

    private fun freeEdge(
        current: CropFractions,
        edge: VideoCropEdge,
        requested: Float,
    ): CropFractions = when (edge) {
        VideoCropEdge.LEFT -> current.copy(
            left = requested.coerceAtMost(1f - current.right - MIN_RETAINED),
        )
        VideoCropEdge.RIGHT -> current.copy(
            right = requested.coerceAtMost(1f - current.left - MIN_RETAINED),
        )
        VideoCropEdge.TOP -> current.copy(
            top = requested.coerceAtMost(1f - current.bottom - MIN_RETAINED),
        )
        VideoCropEdge.BOTTOM -> current.copy(
            bottom = requested.coerceAtMost(1f - current.top - MIN_RETAINED),
        )
    }.sanitized()

    private fun changeVerticalEdge(
        current: CropFractions,
        edge: VideoCropEdge,
        requested: Float,
        sourceWidth: Int,
        sourceHeight: Int,
        targetRatio: Float,
    ): CropFractions {
        var left = if (edge == VideoCropEdge.LEFT) requested else current.left
        var right = if (edge == VideoCropEdge.RIGHT) requested else current.right
        var keptWidth = (1f - left - right).coerceAtLeast(MIN_RETAINED)

        val maxWidthForFullHeight =
            (targetRatio * sourceHeight / sourceWidth.toFloat()).coerceAtMost(1f)
        if (keptWidth > maxWidthForFullHeight) {
            keptWidth = maxWidthForFullHeight
            if (edge == VideoCropEdge.LEFT) left = 1f - right - keptWidth
            else right = 1f - left - keptWidth
        }

        val keptHeight =
            (sourceWidth * keptWidth / (targetRatio * sourceHeight)).coerceIn(MIN_RETAINED, 1f)
        val centerY = (current.top + (1f - current.bottom)) / 2f
        val (top, bottom) = centeredCrop(keptHeight, centerY)
        return CropFractions(left, right, top, bottom).sanitized()
    }

    private fun changeHorizontalEdge(
        current: CropFractions,
        edge: VideoCropEdge,
        requested: Float,
        sourceWidth: Int,
        sourceHeight: Int,
        targetRatio: Float,
    ): CropFractions {
        var top = if (edge == VideoCropEdge.TOP) requested else current.top
        var bottom = if (edge == VideoCropEdge.BOTTOM) requested else current.bottom
        var keptHeight = (1f - top - bottom).coerceAtLeast(MIN_RETAINED)

        val maxHeightForFullWidth =
            (sourceWidth / (targetRatio * sourceHeight.toFloat())).coerceAtMost(1f)
        if (keptHeight > maxHeightForFullWidth) {
            keptHeight = maxHeightForFullWidth
            if (edge == VideoCropEdge.TOP) top = 1f - bottom - keptHeight
            else bottom = 1f - top - keptHeight
        }

        val keptWidth =
            (targetRatio * sourceHeight * keptHeight / sourceWidth).coerceIn(MIN_RETAINED, 1f)
        val centerX = (current.left + (1f - current.right)) / 2f
        val (left, right) = centeredCrop(keptWidth, centerX)
        return CropFractions(left, right, top, bottom).sanitized()
    }

    private fun centeredCrop(kept: Float, requestedCenter: Float): Pair<Float, Float> {
        val size = kept.coerceIn(MIN_RETAINED, 1f)
        val half = size / 2f
        val center = requestedCenter.coerceIn(half, 1f - half)
        val start = center - half
        val end = center + half
        return start to (1f - end)
    }

    private fun CropFractions.sanitized(): CropFractions {
        val l = left.coerceIn(0f, MAX_EDGE)
        val r = right.coerceIn(0f, min(MAX_EDGE, 1f - l - MIN_RETAINED))
        val t = top.coerceIn(0f, MAX_EDGE)
        val b = bottom.coerceIn(0f, min(MAX_EDGE, 1f - t - MIN_RETAINED))
        return CropFractions(l, r, t, b)
    }
}

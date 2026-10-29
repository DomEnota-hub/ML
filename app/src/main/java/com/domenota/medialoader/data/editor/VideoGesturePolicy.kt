package com.domenota.medialoader.data.editor

import kotlin.math.abs

internal enum class VideoGestureMode { NONE, SEEK, BRIGHTNESS, VOLUME }

internal object VideoGesturePolicy {
    fun intentionalMode(startX: Float, totalX: Float, totalY: Float, width: Float,
        thresholdPx: Float, startY: Float = 1f, height: Float = 1f): VideoGestureMode {
        val horizontal = abs(totalX)
        val vertical = abs(totalY)
        if (maxOf(horizontal, vertical) < thresholdPx) return VideoGestureMode.NONE
        if (horizontal < vertical * 1.5f && vertical < horizontal * 1.5f)
            return VideoGestureMode.NONE
        return mode(startX, totalX, totalY, width, startY, height)
    }

    fun mode(startX: Float, deltaX: Float, deltaY: Float, width: Float,
        startY: Float = 1f, height: Float = 1f): VideoGestureMode {
        if (width <= 0f) return VideoGestureMode.NONE
        if (abs(deltaX) >= abs(deltaY)) return if (height > 0f && startY >= height * 0.5f)
            VideoGestureMode.SEEK else VideoGestureMode.NONE
        return when {
            startX < width * 0.15f -> VideoGestureMode.BRIGHTNESS
            startX > width * 0.85f -> VideoGestureMode.VOLUME
            else -> VideoGestureMode.NONE
        }
    }

    fun level(start: Float, deltaY: Float, height: Float): Float =
        if (height <= 0f) start.coerceIn(0f, 1f)
        else (start - deltaY / height).coerceIn(0f, 1f)

    fun guardedLevel(start: Float, deltaY: Float, height: Float): Float =
        level(start, deltaY, height)
}

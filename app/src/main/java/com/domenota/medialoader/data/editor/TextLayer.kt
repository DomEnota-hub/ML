package com.domenota.medialoader.data.editor

import java.util.UUID

/** Coordinates and size refer to the final cropped/rotated image. Times refer to the exported clip. */
data class TextLayer(
    val id: String = UUID.randomUUID().toString(),
    val text: String = "Ваш текст",
    val fontId: String = "OpenSans-Regular",
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val size: Float = 0.065f,
    val rotation: Float = 0f,
    val color: Int = -1,
    val outlineColor: Int = -16777216,
    val outline: Float = 0.06f,
    val startMs: Long = 0L,
    val endMs: Long? = null,
) {
    init {
        require(text.length <= 500)
        require(centerX.isFinite() && centerX in 0f..1f && centerY.isFinite() && centerY in 0f..1f)
        require(size.isFinite() && size in 0.02f..0.25f)
        require(rotation.isFinite() && rotation in -180f..180f)
        require(outline.isFinite() && outline in 0f..0.15f)
        require(startMs >= 0L && (endMs == null || endMs > startMs))
    }
    fun visibleAt(positionMs: Long): Boolean = text.isNotBlank() && positionMs >= startMs &&
        (endMs == null || positionMs < endMs)
    fun within(durationMs: Long): TextLayer? {
        if (durationMs <= startMs) return null
        return copy(endMs = endMs?.coerceAtMost(durationMs))
    }
}

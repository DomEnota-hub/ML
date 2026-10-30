package com.domenota.medialoader.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.editor.CropAspectPreset
import com.domenota.medialoader.data.editor.CropFractions
import com.domenota.medialoader.data.editor.VideoCropHandle
import com.domenota.medialoader.data.editor.VideoCropMath
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun CropFrameOverlay(
    crop: CropFractions,
    sourceWidth: Int,
    sourceHeight: Int,
    preset: CropAspectPreset,
    onCropChange: (CropFractions) -> Unit,
    modifier: Modifier = Modifier,
    showFrame: Boolean = true,
    onCompareOriginalChange: ((Boolean) -> Unit)? = null,
    onCropChangeFinished: () -> Unit = {},
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var activeHandle by remember { mutableStateOf<VideoCropHandle?>(null) }
    val latestCrop by rememberUpdatedState(crop)
    val latestPreset by rememberUpdatedState(preset)
    val latestCompareOriginal by rememberUpdatedState(onCompareOriginalChange)
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val edgeHitRadiusPx = with(density) { 36.dp.toPx() }
    val cornerHitRadiusPx = with(density) { 64.dp.toPx() }

    Canvas(
        modifier = modifier
            .onSizeChanged { containerSize = it }
            .pointerInput(containerSize, sourceWidth, sourceHeight, edgeHitRadiusPx, cornerHitRadiusPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val imageRect = fittedImageRect(containerSize, sourceWidth, sourceHeight)
                    val handle = hitTestCropHandle(
                        point = down.position,
                        crop = latestCrop,
                        imageRect = imageRect,
                        edgeHitRadius = edgeHitRadiusPx,
                        cornerHitRadius = cornerHitRadiusPx,
                    )
                    activeHandle = handle
                    if (handle != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    if (handle == null || imageRect.width <= 0f || imageRect.height <= 0f) {
                        val compareOriginal = latestCompareOriginal
                        if (handle == null && compareOriginal != null) {
                            val cancelledBeforeLongPress = withTimeoutOrNull(
                                viewConfiguration.longPressTimeoutMillis,
                            ) {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                        ?: return@withTimeoutOrNull true
                                    if (!change.pressed ||
                                        change.isConsumed ||
                                        (change.position - down.position).getDistance() >
                                        viewConfiguration.touchSlop
                                    ) {
                                        return@withTimeoutOrNull true
                                    }
                                }
                            }
                            if (cancelledBeforeLongPress == null) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                compareOriginal(true)
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                            ?: break
                                        if (!change.pressed) break
                                        change.consume()
                                    }
                                } finally {
                                    compareOriginal(false)
                                }
                            }
                        }
                        return@awaitEachGesture
                    }

                    var previous = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val delta = change.position - previous
                        if (delta != Offset.Zero) {
                            onCropChange(
                                VideoCropMath.changeHandle(
                                    current = latestCrop,
                                    handle = handle,
                                    deltaXFraction = delta.x / imageRect.width,
                                    deltaYFraction = delta.y / imageRect.height,
                                    sourceWidth = sourceWidth,
                                    sourceHeight = sourceHeight,
                                    preset = latestPreset,
                                ),
                            )
                            change.consume()
                            previous = change.position
                        }
                    }
                    activeHandle = null
                    onCropChangeFinished()
                }
            },
    ) {
        if (!showFrame) return@Canvas
        val imageRect = fittedImageRect(containerSize, sourceWidth, sourceHeight)
        if (imageRect.width <= 0f || imageRect.height <= 0f) return@Canvas

        val left = imageRect.left + imageRect.width * crop.left
        val right = imageRect.left + imageRect.width * (1f - crop.right)
        val top = imageRect.top + imageRect.height * crop.top
        val bottom = imageRect.top + imageRect.height * (1f - crop.bottom)
        val shade = Color.Black.copy(alpha = 0.58f)

        drawRect(
            shade,
            topLeft = imageRect.topLeft,
            size = Size(imageRect.width, (top - imageRect.top).coerceAtLeast(0f)),
        )
        drawRect(
            shade,
            topLeft = Offset(imageRect.left, bottom),
            size = Size(imageRect.width, (imageRect.bottom - bottom).coerceAtLeast(0f)),
        )
        drawRect(
            shade,
            topLeft = Offset(imageRect.left, top),
            size = Size((left - imageRect.left).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f)),
        )
        drawRect(
            shade,
            topLeft = Offset(right, top),
            size = Size((imageRect.right - right).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f)),
        )

        drawRect(
            Color.White,
            topLeft = Offset(left, top),
            size = Size((right - left).coerceAtLeast(1f), (bottom - top).coerceAtLeast(1f)),
            style = Stroke(width = 2.dp.toPx()),
        )

        val cornerRadius = 5.dp.toPx()
        listOf(
            Offset(left, top),
            Offset(right, top),
            Offset(left, bottom),
            Offset(right, bottom),
        ).forEach { point ->
            drawCircle(Color.White, radius = cornerRadius, center = point)
        }
    }
}

private fun fittedImageRect(
    container: IntSize,
    sourceWidth: Int,
    sourceHeight: Int,
): Rect {
    if (container.width <= 0 || container.height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
        return Rect.Zero
    }
    val scale = min(
        container.width.toFloat() / sourceWidth,
        container.height.toFloat() / sourceHeight,
    )
    val width = sourceWidth * scale
    val height = sourceHeight * scale
    val left = (container.width - width) / 2f
    val top = (container.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}

private fun hitTestCropHandle(
    point: Offset,
    crop: CropFractions,
    imageRect: Rect,
    edgeHitRadius: Float,
    cornerHitRadius: Float,
): VideoCropHandle? {
    val left = imageRect.left + imageRect.width * crop.left
    val right = imageRect.left + imageRect.width * (1f - crop.right)
    val top = imageRect.top + imageRect.height * crop.top
    val bottom = imageRect.top + imageRect.height * (1f - crop.bottom)

    val corners = listOf(
        VideoCropHandle.TOP_LEFT to Offset(left, top),
        VideoCropHandle.TOP_RIGHT to Offset(right, top),
        VideoCropHandle.BOTTOM_LEFT to Offset(left, bottom),
        VideoCropHandle.BOTTOM_RIGHT to Offset(right, bottom),
    )
    corners.firstOrNull { (_, corner) ->
        hypot(point.x - corner.x, point.y - corner.y) <= cornerHitRadius
    }?.let { return it.first }

    if (point.y in (top - edgeHitRadius)..(bottom + edgeHitRadius)) {
        if (abs(point.x - left) <= edgeHitRadius) return VideoCropHandle.LEFT
        if (abs(point.x - right) <= edgeHitRadius) return VideoCropHandle.RIGHT
    }
    if (point.x in (left - edgeHitRadius)..(right + edgeHitRadius)) {
        if (abs(point.y - top) <= edgeHitRadius) return VideoCropHandle.TOP
        if (abs(point.y - bottom) <= edgeHitRadius) return VideoCropHandle.BOTTOM
    }
    return null
}

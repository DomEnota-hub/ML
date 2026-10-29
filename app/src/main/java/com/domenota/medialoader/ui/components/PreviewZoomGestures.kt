package com.domenota.medialoader.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.domenota.medialoader.data.editor.PreviewZoom

/** Initial pass lets two fingers take over from a text drag or color picker safely. */
internal fun Modifier.previewZoomGestures(current: () -> PreviewZoom, imageDimensions: () -> Pair<Int, Int>?,
    onChange: (PreviewZoom) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var transforming = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.isConsumed }) break
                if (event.changes.count { it.pressed } >= 2) {
                    transforming = true
                    val pivot = event.calculateCentroid(useCurrent = false)
                    val pan = event.calculatePan()
                    val image = imageDimensions()
                    if (pivot != Offset.Unspecified) onChange(current().transform(event.calculateZoom(),
                        pan.x, pan.y, pivot.x, pivot.y, size.width.toFloat(), size.height.toFloat(),
                        image?.first ?: 0, image?.second ?: 0))
                }
                if (transforming) event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }

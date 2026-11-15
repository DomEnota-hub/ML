package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp

/** Temporary speed is deliberately separate from the player's remembered user preference. */
internal class VideoHoldSpeedSession {
    private var previousSpeed: Float? = null
    val active: Boolean get() = previousSpeed != null

    fun begin(speed: Float, playing: Boolean, apply: (Float) -> Unit): Boolean {
        if (active || !playing || !speed.isFinite() || speed <= 0f) return false
        previousSpeed = speed // Mark active before Media3 dispatches its speed listener.
        apply(VideoHoldSpeedPolicy.SPEED)
        return true
    }

    fun end(apply: (Float) -> Unit) {
        val previous = previousSpeed ?: return
        previousSpeed = null // Idempotent, including disposal and cancelled pointer input.
        apply(previous)
    }
}

internal object VideoHoldSpeedPolicy {
    const val SPEED = 2f
    const val HOLD_MS = 400L
    fun isRightZone(x: Float, width: Int): Boolean =
        width > 0 && x > width * .58f && x <= width
}

private sealed interface CanvasTapEnd {
    data class Up(val change: PointerInputChange) : CanvasTapEnd
    data object Cancelled : CanvasTapEnd
    data object Hold : CanvasTapEnd
}

/** Wait without stealing existing drag/pager gestures; reject multiple fingers and large motion. */
private suspend fun AwaitPointerEventScope.awaitCanvasTapEnd(down: PointerInputChange): CanvasTapEnd {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        val change = event.changes.firstOrNull { it.id == down.id } ?: return CanvasTapEnd.Cancelled
        if (event.changes.any { it.id != down.id && it.pressed } || change.isConsumed ||
            (change.position - down.position).getDistance() > viewConfiguration.touchSlop ||
            change.position.x !in 0f..size.width.toFloat() || change.position.y !in 0f..size.height.toFloat()
        ) return CanvasTapEnd.Cancelled
        if (change.changedToUp()) return CanvasTapEnd.Up(change)
        if (!change.pressed) return CanvasTapEnd.Cancelled
        val final = awaitPointerEvent(PointerEventPass.Final)
        if (final.changes.any { it.isConsumed }) return CanvasTapEnd.Cancelled
    }
}

private suspend fun AwaitPointerEventScope.awaitTapOrHold(down: PointerInputChange): CanvasTapEnd =
    if (VideoHoldSpeedPolicy.isRightZone(down.position.x, size.width)) {
        withTimeoutOrNull(VideoHoldSpeedPolicy.HOLD_MS) { awaitCanvasTapEnd(down) } ?: CanvasTapEnd.Hold
    } else awaitCanvasTapEnd(down)

/** Only one recognizer owns tap, double tap and hold, so a boost never also seeks/toggles chrome. */
internal suspend fun PointerInputScope.detectVideoCanvasTaps(
    onTap: () -> Unit,
    onDoubleTap: (Offset) -> Unit,
    onHoldStart: () -> Boolean,
    onHoldEnd: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        val first = awaitTapOrHold(down)
        when (first) {
            CanvasTapEnd.Hold -> {
                val started = onHoldStart()
                try {
                    // Even a refused hold (paused/locked) must not turn into an ordinary tap.
                    (awaitCanvasTapEnd(down) as? CanvasTapEnd.Up)?.change?.consume()
                } finally { if (started) onHoldEnd() }
                return@awaitEachGesture
            }
            CanvasTapEnd.Cancelled -> return@awaitEachGesture
            is CanvasTapEnd.Up -> first.change.consume()
        }
        // Match the existing Compose double-tap timing, including its minimum separation.
        val secondDown = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
            val minTime = (first as CanvasTapEnd.Up).change.uptimeMillis + viewConfiguration.doubleTapMinTimeMillis
            var next = awaitFirstDown()
            while (next.uptimeMillis < minTime) next = awaitFirstDown()
            next
        }
        if (secondDown == null) {
            onTap()
            return@awaitEachGesture
        }
        secondDown.consume()
        when (val second = awaitTapOrHold(secondDown)) {
            CanvasTapEnd.Hold -> {
                val started = onHoldStart()
                try { (awaitCanvasTapEnd(secondDown) as? CanvasTapEnd.Up)?.change?.consume() }
                finally { if (started) onHoldEnd() }
            }
            CanvasTapEnd.Cancelled -> onTap()
            is CanvasTapEnd.Up -> {
                second.change.consume()
                onDoubleTap(second.change.position)
            }
        }
    }
}

package com.domenota.medialoader.data.playback

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle
import androidx.media3.common.Player

internal object FloatingVideoSeekPolicy {
    fun target(x: Float, width: Int, duration: Long): Long? {
        if (!x.isFinite() || width <= 0 || duration <= 0) return null
        return ((x.toDouble() / width).coerceIn(0.0, 1.0) * duration).toLong().coerceIn(0L, duration)
    }
}

/** A thin visible track with a 32dp touch target. Owns its touch stream, never forwards scrub
 * events to window drag/resize. Seeking does not call play or pause. Callbacks exist only while attached.
 */
@SuppressLint("ViewConstructor")
internal class FloatingVideoSeekBar(context: Context, private val player: Player,
    private val onScrubbing: (Boolean) -> Unit = {}) : View(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var attached = false
    private var scrubbing = false
    private var target: Long? = null
    private var scrubDuration = 0L
    private var observedDuration = 0L
    private var observedPosition = 0L
    private val tick = object : Runnable {
        override fun run() {
            if (!attached) return
            if (!scrubbing) {
                observedDuration = player.duration
                observedPosition = player.currentPosition
                invalidate()
            }
            handler.postDelayed(this, 250)
        }
    }
    init { contentDescription = "Перемотка видео"; isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); attached = true; handler.post(tick) }
    override fun onDetachedFromWindow() {
        attached = false; handler.removeCallbacks(tick); finishScrub(); super.onDetachedFromWindow()
    }
    override fun onDraw(canvas: Canvas) {
        val duration = if (scrubbing) scrubDuration else observedDuration
        val position = target ?: observedPosition
        val fraction = if (duration > 0) (position.toDouble() / duration).coerceIn(0.0, 1.0).toFloat() else 0f
        val thickness = resources.displayMetrics.density * 3
        val y = height - thickness / 2
        paint.strokeWidth = thickness; paint.color = 0x77FFFFFF
        canvas.drawLine(0f, y, width.toFloat(), y, paint)
        paint.color = Color.WHITE
        canvas.drawLine(0f, y, width * fraction, y, paint)
        if (scrubbing) canvas.drawCircle((width * fraction).coerceIn(thickness, (width - thickness).coerceAtLeast(thickness)), y,
            resources.displayMetrics.density * 5, paint)
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!player.isCurrentMediaItemSeekable) return false
                scrubDuration = player.duration
                target = FloatingVideoSeekPolicy.target(event.x, width, scrubDuration) ?: return false
                scrubbing = true; parent?.requestDisallowInterceptTouchEvent(true); onScrubbing(true)
            }
            MotionEvent.ACTION_MOVE -> if (scrubbing) target = FloatingVideoSeekPolicy.target(event.x, width, scrubDuration)
                else return false
            MotionEvent.ACTION_UP -> {
                if (!scrubbing) return false
                if (player.isCurrentMediaItemSeekable && player.duration > 0) {
                    FloatingVideoSeekPolicy.target(event.x, width, minOf(scrubDuration, player.duration))?.let(player::seekTo)
                }
                finishScrub(); performClick()
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> { if (!scrubbing) return false; finishScrub() }
            else -> return scrubbing
        }
        invalidate(); return true
    }
    private fun finishScrub() {
        if (!scrubbing) return
        scrubbing = false; target = null; parent?.requestDisallowInterceptTouchEvent(false); onScrubbing(false); invalidate()
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        val duration = player.duration
        info.isEnabled = duration > 0 && player.isCurrentMediaItemSeekable
        if (info.isEnabled) {
            info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
                0f, duration.toFloat(), player.currentPosition.coerceIn(0L, duration).toFloat())
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
        }
    }
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id) {
            val position = arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) ?: return false
            if (!position.isFinite() || player.duration <= 0 || !player.isCurrentMediaItemSeekable) return false
            player.seekTo(position.toLong().coerceIn(0L, player.duration)); invalidate(); return true
        }
        return super.performAccessibilityAction(action, arguments)
    }
}

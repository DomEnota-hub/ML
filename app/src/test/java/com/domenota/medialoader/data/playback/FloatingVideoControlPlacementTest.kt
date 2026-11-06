package com.domenota.medialoader.data.playback

import android.app.Activity
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class FloatingVideoControlPlacementTest {
    @Test fun expandButtonAnchorsToBottomEndAndReceivesTapsAboveSeekbar() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val density = 1f
        val container = FrameLayout(activity)
        var scrubTouches = 0
        val seek = View(activity).apply {
            // A plain View is non-clickable by default; emulate the real seekbar's
            // touch ownership instead of letting parent hit testing drop its events.
            isClickable = true
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) scrubTouches++
                true
            }
        }
        container.addView(seek, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FloatingVideoControlPlacement.SEEK_TOUCH_HEIGHT_DP,
            Gravity.BOTTOM,
        ))
        var opens = 0
        val expand = View(activity).apply {
            setOnClickListener { opens++ }
        }
        container.addView(expand,
            FloatingVideoControlPlacement.expandLayoutParams(density))
        activity.setContentView(container)
        container.measure(
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
        )
        container.layout(0, 0, 300, 200)

        assertEquals(300 - FloatingVideoControlPlacement.EXPAND_END_DP, expand.right)
        assertEquals(200 - FloatingVideoControlPlacement.EXPAND_BOTTOM_DP, expand.bottom)
        assertEquals(FloatingVideoControlPlacement.EXPAND_TARGET_DP, expand.width)
        assertTrue(expand.bottom > seek.top)

        fun tap(x: Float, y: Float) {
            val down = MotionEvent.obtain(1L, 1L, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(1L, 15L, MotionEvent.ACTION_UP, x, y, 0)
            container.dispatchTouchEvent(down)
            container.dispatchTouchEvent(up)
            // Android schedules click delivery on the UI looper after ACTION_UP.
            shadowOf(Looper.getMainLooper()).idle()
            down.recycle()
            up.recycle()
        }
        tap(expand.left + expand.width / 2f, expand.top + expand.height / 2f)
        assertEquals(1, opens)
        assertEquals(0, scrubTouches)

        tap(100f, 190f)
        assertEquals(1, opens)
        assertEquals(1, scrubTouches)
        activity.finish()
    }

    @Test fun cornerMarginsScaleWithDisplayDensity() {
        val p = FloatingVideoControlPlacement.expandLayoutParams(2f)
        assertEquals(88, p.width)
        assertEquals(88, p.height)
        assertEquals(16, p.marginEnd)
        assertEquals(20, p.bottomMargin)
    }
}

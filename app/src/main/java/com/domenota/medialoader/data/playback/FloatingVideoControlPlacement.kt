package com.domenota.medialoader.data.playback

import android.view.Gravity
import android.widget.FrameLayout

/** Floating player controls are positioned relative to the player window, not the screen. */
internal object FloatingVideoControlPlacement {
    const val EXPAND_TARGET_DP = 44
    const val EXPAND_END_DP = 8
    const val EXPAND_BOTTOM_DP = 10
    const val SEEK_TOUCH_HEIGHT_DP = 32

    fun expandLayoutParams(density: Float): FrameLayout.LayoutParams {
        fun dp(value: Int) = (value * density).toInt()
        return FrameLayout.LayoutParams(
            dp(EXPAND_TARGET_DP), dp(EXPAND_TARGET_DP),
            Gravity.END or Gravity.BOTTOM,
        ).apply {
            marginEnd = dp(EXPAND_END_DP)
            bottomMargin = dp(EXPAND_BOTTOM_DP)
        }
    }
}

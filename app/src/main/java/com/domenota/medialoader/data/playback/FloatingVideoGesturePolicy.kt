package com.domenota.medialoader.data.playback

internal object FloatingVideoGesturePolicy {
    const val MIN_SEEK_WIDTH_DP = 280

    fun maxWidth(screenWidthPx: Int): Int = screenWidthPx.coerceAtLeast(0)

    fun clampX(x: Int, windowWidthPx: Int, screenWidthPx: Int): Int =
        x.coerceIn(0, (screenWidthPx - windowWidthPx).coerceAtLeast(0))

    /** A small window keeps double taps inert; a larger one uses wide side zones. */
    fun seekDelta(windowWidthPx: Int, density: Float, tapX: Float): Long? {
        if (windowWidthPx < MIN_SEEK_WIDTH_DP * density) return null
        return when {
            tapX < windowWidthPx * .44f -> -10_000L
            tapX > windowWidthPx * .56f -> 10_000L
            else -> null
        }
    }
}

package com.domenota.medialoader.ui.screens

/** Changes only the affordance's visibility; never changes locked playback. */
internal object VideoLockVisibility {
    fun afterCanvasTap(isVisible: Boolean): Boolean = !isVisible
}

package com.domenota.medialoader.ui.screens

internal object VideoPlayerUiPolicy {
    const val CONTROLS_TIMEOUT_MS = 3_000L
    const val NATIVE_CONTROLLER_TIMEOUT_MS = 3_000

    fun shouldScheduleHide(
        isPlaying: Boolean,
        chromeVisible: Boolean,
        controlsLocked: Boolean,
    ): Boolean = isPlaying && chromeVisible && !controlsLocked

    fun shouldRestartFromBeginning(playbackEnded: Boolean): Boolean = playbackEnded
}

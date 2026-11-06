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

    /** The second Previous tap after a restart sees a position within the first 3 seconds. */
    const val PREVIOUS_VIDEO_THRESHOLD_MS = 3_000L

    enum class PreviousAction { RESTART, OPEN_PREVIOUS }

    fun previousAction(positionMs: Long, hasPreviousVideo: Boolean): PreviousAction =
        if (hasPreviousVideo && positionMs in 0L..PREVIOUS_VIDEO_THRESHOLD_MS)
            PreviousAction.OPEN_PREVIOUS
        else PreviousAction.RESTART

    /** Navigate in the viewer's existing sorted order; skip photos, never wrap at album ends. */
    fun adjacentVideoIndex(mediaTypes: List<com.domenota.medialoader.core.model.MediaType>,
        currentIndex: Int, direction: Int): Int? {
        if (currentIndex !in mediaTypes.indices || direction !in listOf(-1, 1)) return null
        var index = currentIndex + direction
        while (index in mediaTypes.indices) {
            if (mediaTypes[index] == com.domenota.medialoader.core.model.MediaType.VIDEO) return index
            index += direction
        }
        return null
    }
}

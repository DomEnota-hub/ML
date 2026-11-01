package com.domenota.medialoader.ui.screens.gallery

/**
 * Don't treat the existing 'scroll to top' counter as a NEW request when
 * Compose rebuilds the gallery after returning from a media viewer.
 */
internal class GalleryScrollRequestTracker(initialRequest: Int) {
    private var lastSeenRequest = initialRequest

    fun shouldScrollToTop(request: Int, mediaTabVisible: Boolean): Boolean {
        if (request == lastSeenRequest) return false
        lastSeenRequest = request
        return mediaTabVisible
    }
}

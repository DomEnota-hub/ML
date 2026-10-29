package com.domenota.medialoader.ui.navigation

internal object BottomTabPolicy {
    fun shouldScrollDownloadsToTop(
        currentRoute: String?,
        destination: AppDestination,
    ): Boolean = currentRoute == AppDestination.Downloads.route &&
        destination == AppDestination.Downloads
}

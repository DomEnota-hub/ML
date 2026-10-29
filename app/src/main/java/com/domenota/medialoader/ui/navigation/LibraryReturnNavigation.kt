package com.domenota.medialoader.ui.navigation

import androidx.navigation.NavController

/** Pop the existing Home entry: restoring a saved Home stack can reopen Settings. */
internal fun returnToLibrary(nav: NavController) {
    if (nav.currentDestination?.route == AppDestination.Home.route) return
    if (!nav.popBackStack(AppDestination.Home.route, false)) nav.navigate(AppDestination.Home.route) {
        launchSingleTop = true
    }
}

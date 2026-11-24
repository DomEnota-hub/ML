package com.domenota.medialoader.ui.navigation

import androidx.navigation.NavController

/** Pop the existing Home entry: restoring a saved Home stack can reopen Settings. */
internal fun returnToLibrary(nav: NavController) {
    if (nav.currentDestination?.route == AppDestination.Home.route) return
    if (!nav.popBackStack(AppDestination.Home.route, false)) nav.navigate(AppDestination.Home.route) {
        launchSingleTop = true
    }
}

/** Drawer transitions are limited to the two existing top-level route entries.
 * Save Downloads when leaving, and restore only Downloads when returning. */
internal fun returnDrawerHome(nav:NavController){
    if(nav.currentDestination?.route==AppDestination.Home.route)return
    if(!nav.popBackStack(AppDestination.Home.route,false,saveState=true))
        nav.navigate(AppDestination.Home.route){launchSingleTop=true}
}
internal fun openDrawerDownloads(nav:NavController){
    if(nav.currentDestination?.route==AppDestination.Downloads.route)return
    nav.navigate(AppDestination.Downloads.route){launchSingleTop=true;restoreState=true}
}

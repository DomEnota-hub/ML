package com.domenota.medialoader.ui.screens.gallery

import android.content.pm.ActivityInfo

/**
 * Manual gallery rotate action enters sensor landscape, allowing BOTH
 * 90° and 270° viewing, even when the system's auto-rotate is disabled.
 * The reverse action returns to portrait. Locking player controls does
 * not affect orientation.
 */
internal fun galleryViewerRequestedOrientation(isLandscape: Boolean): Int =
    if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

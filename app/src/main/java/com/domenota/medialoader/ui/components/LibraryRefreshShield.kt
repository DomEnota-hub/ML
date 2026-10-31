package com.domenota.medialoader.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Library scans are exclusively background work. Never draw a blocking loading
 * overlay, spinner or modal on top of photo/music content. Permission requests
 * are handled by the Android system and are not part of this component.
 */
@Composable
internal fun LibraryRefreshShield(
    @Suppress("UNUSED_PARAMETER") loading: Boolean,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") onExit: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize()) { content() }
}

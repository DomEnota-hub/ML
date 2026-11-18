package com.domenota.medialoader.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Anchors to Scaffold's measured bottom chrome, including an active player.
 * Consume its bottom space before applying safe insets: this takes the greater
 * of chrome / IME / system bars instead of adding the navigation inset twice.
 * No panel height assumption is needed when text or orientation changes.
 */
@Composable
internal fun CollapsedTransferPanelHost(
    chromeBottom: Dp,
    modifier: Modifier = Modifier,
    safeInsets: WindowInsets = WindowInsets.safeDrawing,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier.fillMaxSize()
            .padding(bottom = chromeBottom)
            .consumeWindowInsets(PaddingValues(bottom = chromeBottom))
            .windowInsetsPadding(safeInsets)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.BottomCenter,
        content = content,
    )
}

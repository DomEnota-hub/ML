package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt

/** Swipes are additive actions, not toggles: RIGHT = favorite, LEFT = private. */
internal enum class HistorySwipeAction { FAVORITE, PRIVATE }

internal fun historySwipeAction(deltaPx: Float, thresholdPx: Float): HistorySwipeAction? =
    when {
        deltaPx >= thresholdPx -> HistorySwipeAction.FAVORITE
        deltaPx <= -thresholdPx -> HistorySwipeAction.PRIVATE
        else -> null
}

/**
 * Only used for completed downloads in LIST mode and group headers.
 * Horizontal dragging wins only after horizontal touch slop, so scrolling
 * vertically through the download history remains unchanged.
 * The existing overflow menus still expose the same actions without gestures.
 */
@Composable
internal fun HistorySwipeRow(
    enabled: Boolean,
    onFavorite: () -> Unit,
    onPrivate: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    var dragPx by remember { mutableFloatStateOf(0f) }
    val thresholdPx = with(LocalDensity.current) { 88.dp.toPx() }
    val maxDragPx = thresholdPx * 1.45f
    val favoriteCallback by rememberUpdatedState(onFavorite)
    val privateCallback by rememberUpdatedState(onPrivate)
    val color = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
        Row(
            modifier = Modifier.matchParentSize()
                .background(if (dragPx >= 0f) color.primary.copy(alpha = .55f)
                    else color.surfaceVariant)
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Star, contentDescription = null, tint = color.onSurface)
                Text(" Избранное", color = color.onSurface)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Личное ", color = color.onSurface)
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = color.onSurface)
            }
        }
        Box(
            Modifier.fillMaxWidth()
                .offset { IntOffset(dragPx.roundToInt(), 0) }
                .pointerInput(thresholdPx) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            dragPx = (dragPx + amount).coerceIn(-maxDragPx, maxDragPx)
                        },
                        onDragEnd = {
                            val target = historySwipeAction(dragPx, thresholdPx)
                            dragPx = 0f
                            when (target) {
                                HistorySwipeAction.FAVORITE -> favoriteCallback()
                                HistorySwipeAction.PRIVATE -> privateCallback()
                                null -> Unit
                            }
                        },
                        onDragCancel = { dragPx = 0f },
                    )
                },
        ) { content() }
    }
}

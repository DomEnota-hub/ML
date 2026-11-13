package com.domenota.medialoader.ui.screens.music

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.ui.theme.musicPlaybackAccent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

internal object MusicFastScrollPolicy {
    fun index(fraction: Float, count: Int): Int = if (count <= 1) 0 else (fraction.coerceIn(0f, 1f) * (count - 1)).roundToInt()
    fun fraction(index: Int, count: Int): Float = if (count <= 1) 0f else index.coerceIn(0, count - 1).toFloat() / (count - 1)
}

/** The hit target exists only while the list is scrolling or the thumb is held. */
@Composable
internal fun MusicFastScroll(state: LazyListState, labels: List<String>, modifier: Modifier = Modifier,
    tag: String = "music-fast-scroll", description: String = "Быстрая прокрутка музыки") {
    LibraryFastScroll(state, state.layoutInfo.totalItemsCount, state.firstVisibleItemIndex,
        state.canScrollForward, state.canScrollBackward, state.isScrollInProgress, labels,
        modifier, tag, description) { index ->
        state.scrollToItem(index.coerceIn(0, (state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)))
    }
}

@Composable
internal fun GalleryFastScroll(state: LazyGridState, labels: List<String>, modifier: Modifier = Modifier) {
    LibraryFastScroll(state, state.layoutInfo.totalItemsCount, state.firstVisibleItemIndex,
        state.canScrollForward, state.canScrollBackward, state.isScrollInProgress, labels,
        modifier, "gallery-fast-scroll", "Быстрая прокрутка галереи") { index ->
        state.scrollToItem(index.coerceIn(0, (state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)))
    }
}

@Composable
private fun LibraryFastScroll(identity: Any, count: Int, firstIndex: Int,
    canForward: Boolean, canBackward: Boolean, scrolling: Boolean, labels: List<String>,
    modifier: Modifier, tag: String, description: String, scrollTo: suspend (Int) -> Unit) {
    val scrollable = count > 1 && (canForward || canBackward)
    val latestScrollTo by rememberUpdatedState(scrollTo)
    var visible by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val targets = remember(identity) { Channel<Int>(Channel.CONFLATED) }
    LaunchedEffect(identity) { targets.receiveAsFlow().collectLatest { latestScrollTo(it) } }
    LaunchedEffect(scrolling, dragging, scrollable) {
        if (!scrollable) visible = false
        else if (scrolling || dragging) visible = true
        else { delay(1700); visible = false }
    }
    if (!scrollable || !visible) return
    val fraction = if (dragging) dragFraction else if (!canForward) 1f else MusicFastScrollPolicy.fraction(firstIndex, count)
    val accent = musicPlaybackAccent
    BoxWithConstraints(modifier.width(36.dp).fillMaxHeight().padding(vertical = 12.dp), contentAlignment = Alignment.TopEnd) {
        val thumb = 40.dp.coerceAtMost(maxHeight)
        val travel = (maxHeight - thumb).coerceAtLeast(0.dp)
        val thumbPx = with(LocalDensity.current) { thumb.toPx() }
        val travelPx = with(LocalDensity.current) { travel.toPx() }
        Box(Modifier.width(4.dp).fillMaxHeight().padding(end = 1.dp).background(accent.copy(alpha = .18f), RoundedCornerShape(2.dp)))
        Box(Modifier.offset(y = travel * fraction).width(8.dp).height(thumb).background(accent, RoundedCornerShape(5.dp)))
        // Reserve a wider touch target than the visible line; it does not overlap the row action menu.
        Box(Modifier.fillMaxSize().testTag(tag).semantics {
            contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            setProgress { value -> targets.trySend(MusicFastScrollPolicy.index(value, count)); true }
        }.pointerInput(identity, count, thumbPx, travelPx) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                dragging = true
                fun move(y: Float) {
                    dragFraction = if (travelPx > 0f) ((y - thumbPx / 2f) / travelPx).coerceIn(0f, 1f) else 0f
                    targets.trySend(MusicFastScrollPolicy.index(dragFraction, count))
                }
                try {
                    move(down.position.y)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        move(change.position.y); change.consume()
                    }
                } finally { dragging = false }
            }
        })
        if (dragging) {
            val label = labels.getOrNull(MusicFastScrollPolicy.index(fraction, count)).orEmpty()
            if (label.isNotBlank()) Text(label, Modifier.align(Alignment.TopEnd).offset(x = (-40).dp, y = travel * fraction)
                .wrapContentWidth(Alignment.End, unbounded = true)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(8.dp), color = accent, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

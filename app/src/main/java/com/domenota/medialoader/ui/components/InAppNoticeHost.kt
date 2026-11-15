package com.domenota.medialoader.ui.components

import android.os.SystemClock
import android.view.ViewTreeObserver
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Only the owning foreground activity admits notices; stopping it discards transient UI. */
@Composable
internal fun NoticeForeground() {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val owner = Any()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> AppNotice.setForeground(owner, true)
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> AppNotice.setForeground(owner, false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        AppNotice.setForeground(owner, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            lifecycle.removeObserver(observer)
            AppNotice.setForeground(owner, false)
        }
    }
}

/** The focused in-app window displays the common state, including modal editing/search windows. */
@Composable
internal fun InAppNoticeHost(modifier: Modifier = Modifier, bottomPadding: Dp = 0.dp) {
    val view = LocalView.current
    var focused by remember(view) { mutableStateOf(view.hasWindowFocus()) }
    DisposableEffect(view) {
        val observer = ViewTreeObserver.OnWindowFocusChangeListener { focused = it }
        view.viewTreeObserver.addOnWindowFocusChangeListener(observer)
        focused = view.hasWindowFocus()
        onDispose {
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(observer)
        }
    }
    val state by AppNotice.state.collectAsState()
    if (!focused || (state.active == null && state.departing == null)) return
    val density = LocalDensity.current
    val systemBottom = with(density) { maxOf(WindowInsets.navigationBars.getBottom(density),
        WindowInsets.ime.getBottom(density)).toDp() }
    Box(modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp,
        bottom = maxOf(bottomPadding, systemBottom) + 8.dp), contentAlignment = Alignment.BottomCenter) {
        state.departing?.let { old ->
            key(old.entry.id) { NoticeCard(old.entry, old.startedAt) }
        }
        state.active?.let { current ->
            key(current.id) { NoticeCard(current, null) }
        }
    }
}

@Composable
internal fun NoticeDialog(onDismissRequest: () -> Unit, properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        Box(contentAlignment = Alignment.Center) {
            content()
            InAppNoticeHost(Modifier.align(Alignment.BottomCenter), bottomPadding = 72.dp)
        }
    }
}

@Composable
internal fun NoticeCard(entry: NoticeEntry, departingAt: Long?, onDismiss: (Long) -> Unit = AppNotice::dismiss) {
    val duration = NoticePolicy.TRANSITION_MS
    val now = SystemClock.uptimeMillis()
    val age = (now - entry.shownAt).coerceAtLeast(0)
    val fadeFraction = ((now - entry.fadeAt).toFloat() / duration).coerceIn(0f, 1f)
    val opacity = remember(entry.id) { Animatable(1f - fadeFraction) }
    val shift = remember(entry.id) { Animatable((1f - age.toFloat() / duration).coerceIn(0f, 1f)) }
    val scope = rememberCoroutineScope()
    var horizontal by remember(entry.id) { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    val swipeDistance = with(LocalDensity.current) { 600.dp.toPx() }
    val outgoing = departingAt != null
    LaunchedEffect(entry.id, departingAt) {
        if (departingAt != null) {
            val remaining = (departingAt + duration - SystemClock.uptimeMillis()).coerceIn(0L, duration).toInt()
            coroutineScope {
                launch { shift.animateTo(-1f, tween(remaining)) }
                launch { opacity.animateTo(0f, tween(remaining)) }
            }
        } else {
            val remaining = (entry.shownAt + duration - SystemClock.uptimeMillis()).coerceIn(0L, duration).toInt()
            shift.animateTo(0f, tween(remaining))
            delay((entry.fadeAt - SystemClock.uptimeMillis()).coerceAtLeast(0))
            opacity.animateTo(0f, tween((entry.expiresAt - SystemClock.uptimeMillis()).coerceIn(0L, duration).toInt()))
        }
    }
    val colors = MaterialTheme.colorScheme
    val accent = when (entry.message.tone) {
        NoticeTone.ERROR -> colors.error
        NoticeTone.WARNING -> colors.primary
        NoticeTone.SUCCESS -> colors.primary
        NoticeTone.INFO -> colors.onSurfaceVariant
    }
    val glyph = when (entry.message.tone) {
        NoticeTone.ERROR -> Icons.Rounded.ErrorOutline
        NoticeTone.WARNING -> Icons.Rounded.WarningAmber
        NoticeTone.SUCCESS -> Icons.Rounded.CheckCircle
        NoticeTone.INFO -> Icons.Rounded.Info
    }
    Surface(
        modifier = Modifier.widthIn(max = 540.dp).fillMaxWidth()
            .graphicsLayer {
                this.alpha = opacity.value
                translationY = shift.value * (size.height + 8.dp.toPx())
                translationX = horizontal
            }
            .then(if (outgoing) Modifier.clearAndSetSemantics {} else
                Modifier.testTag("ml-active-notice").semantics { liveRegion = LiveRegionMode.Polite }
                    .pointerInput(entry.id) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val dismiss = abs(horizontal) >= threshold
                                val end = if (dismiss) (if (horizontal < 0f) -swipeDistance else swipeDistance) else 0f
                                scope.launch {
                                    animate(horizontal, end, animationSpec = tween(180)) { value, _ -> horizontal = value }
                                    if (dismiss) onDismiss(entry.id)
                                }
                            },
                            onDragCancel = { horizontal = 0f },
                            onHorizontalDrag = { change, amount -> change.consume(); horizontal += amount },
                        )
                    }),
        shape = RoundedCornerShape(18.dp),
        color = colors.surfaceContainerHigh,
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.outlineVariant),
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(glyph, null, Modifier.size(24.dp), tint = accent)
            Column(Modifier.weight(1f)) {
                Text(entry.message.title, style = MaterialTheme.typography.bodyMedium)
                entry.message.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            }
            if (!outgoing) IconButton(onClick = { onDismiss(entry.id) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.Close, "Закрыть уведомление", Modifier.size(20.dp), tint = colors.onSurfaceVariant)
            } else Spacer(Modifier.size(48.dp))
        }
    }
}

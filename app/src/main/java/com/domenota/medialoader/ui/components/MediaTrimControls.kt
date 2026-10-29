package com.domenota.medialoader.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlin.math.abs

private enum class TrimHandle { START, END }

@Composable
fun MediaTrimControls(
    player: Player,
    durationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    onTrimChange: (Long, Long) -> Unit,
    modifier: Modifier = Modifier,
    snapIntervalMs: Long? = null,
    timelineVisual: @Composable (Modifier) -> Unit = {},
    scrubPreview: (@Composable (Long) -> Unit)? = null,
) {
    var positionMs by remember(player) { mutableLongStateOf(0L) }
    var isPlaying by remember(player) { mutableStateOf(player.isPlaying) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var activeHandleLabel by remember { mutableStateOf<TrimHandle?>(null) }
    var activePositionMs by remember { mutableLongStateOf(0L) }
    val latestStart by rememberUpdatedState(trimStartMs)
    val latestEnd by rememberUpdatedState(trimEndMs)
    val latestOnTrimChange by rememberUpdatedState(onTrimChange)
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val handleHitPx = with(density) { 44.dp.toPx() }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                positionMs = newPosition.positionMs.coerceAtLeast(0L)
            }
        }
        player.addListener(listener)
        positionMs = player.currentPosition.coerceAtLeast(0L)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player, durationMs, trimStartMs, trimEndMs) {
        while (true) {
            val current = player.currentPosition.coerceAtLeast(0L)
            positionMs = current
            if (player.isPlaying && current >= trimEndMs.coerceAtLeast(trimStartMs + 1L) - 40L) {
                player.pause()
                player.seekTo(trimEndMs)
                positionMs = trimEndMs
            }
            delay(50L)
        }
    }

    val baseColor = MaterialTheme.colorScheme.surfaceVariant
    val selectedColor = MaterialTheme.colorScheme.primary
    val playheadColor = MaterialTheme.colorScheme.onSurface
    val handleColor = MaterialTheme.colorScheme.primary

    Column(modifier) {
        if (activeHandleLabel != null) {
            val prefix = if (activeHandleLabel == TrimHandle.START) "Начало" else "Конец"
            Text(
                "$prefix · ${formatTrimTime(activePositionMs)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        if (activeHandleLabel != null && scrubPreview != null) {
            Box(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                scrubPreview(activePositionMs)
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(76.dp)
                .onSizeChanged { canvasSize = it }
                .pointerInput(durationMs, canvasSize, handleHitPx, snapIntervalMs) {
                    if (durationMs <= 0L || canvasSize.width <= 0) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val width = canvasSize.width.toFloat().coerceAtLeast(1f)
                        fun xFor(ms: Long): Float =
                            (ms.coerceIn(0L, durationMs).toFloat() / durationMs) * width
                        fun timeFor(x: Float): Long {
                            var value = ((x.coerceIn(0f, width) / width) * durationMs)
                                .toLong()
                                .coerceIn(0L, durationMs)
                            value = when {
                                value <= SNAP_EDGE_MS -> 0L
                                durationMs - value <= SNAP_EDGE_MS -> durationMs
                                else -> value
                            }
                            val interval = snapIntervalMs?.takeIf { it > 0L }
                            if (interval != null && value !in listOf(0L, durationMs)) {
                                value = (((value + interval / 2L) / interval) * interval)
                                    .coerceIn(0L, durationMs)
                            }
                            return value
                        }

                        val startX = xFor(latestStart)
                        val endX = xFor(latestEnd)
                        val activeHandle = when {
                            abs(down.position.x - startX) <= handleHitPx &&
                                abs(down.position.x - startX) <= abs(down.position.x - endX) ->
                                TrimHandle.START
                            abs(down.position.x - endX) <= handleHitPx -> TrimHandle.END
                            else -> null
                        }

                        if (activeHandle != null) {
                            player.pause()
                            activeHandleLabel = activeHandle
                            activePositionMs = if (activeHandle == TrimHandle.START) latestStart else latestEnd
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val requested = timeFor(change.position.x)
                                when (activeHandle) {
                                    TrimHandle.START -> {
                                        val end = latestEnd
                                        val next = requested.coerceIn(
                                            0L,
                                            (end - MIN_TRIM_MS).coerceAtLeast(0L),
                                        )
                                        latestOnTrimChange(next, end)
                                        player.seekTo(next)
                                        positionMs = next
                                        activePositionMs = next
                                    }
                                    TrimHandle.END -> {
                                        val start = latestStart
                                        val next = requested.coerceIn(
                                            (start + MIN_TRIM_MS).coerceAtMost(durationMs),
                                            durationMs,
                                        )
                                        latestOnTrimChange(start, next)
                                        player.seekTo(next)
                                        positionMs = next
                                        activePositionMs = next
                                    }
                                }
                                change.consume()
                            }
                            activeHandleLabel = null
                        } else {
                            val origin = down.position
                            var moved = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    if (!moved) {
                                        val target = timeFor(change.position.x)
                                            .coerceIn(latestStart, latestEnd)
                                        player.seekTo(target)
                                        positionMs = target
                                        player.play()
                                    }
                                    break
                                }
                                if ((change.position - origin).getDistance() > viewConfiguration.touchSlop) {
                                    moved = true
                                }
                            }
                        }
                    }
                },
        ) {
            timelineVisual(
                Modifier
                    .fillMaxSize()
                    .padding(vertical = 8.dp),
            )
            Canvas(Modifier.fillMaxSize()) {
            if (durationMs <= 0L) return@Canvas
            val width = size.width.coerceAtLeast(1f)
            val centerY = size.height / 2f
            fun xFor(ms: Long): Float =
                (ms.coerceIn(0L, durationMs).toFloat() / durationMs) * width

            val startX = xFor(trimStartMs)
            val endX = xFor(trimEndMs)
            val playX = xFor(positionMs)

            val shade = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.48f)
            if (startX > 0f) {
                drawRect(
                    color = shade,
                    size = androidx.compose.ui.geometry.Size(startX, size.height),
                )
            }
            if (endX < width) {
                drawRect(
                    color = shade,
                    topLeft = Offset(endX, 0f),
                    size = androidx.compose.ui.geometry.Size(width - endX, size.height),
                )
            }

            drawLine(
                color = baseColor.copy(alpha = 0.72f),
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = 10.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = selectedColor,
                start = Offset(startX, centerY),
                end = Offset(endX, centerY),
                strokeWidth = 10.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = handleColor,
                start = Offset(startX, centerY - 26.dp.toPx()),
                end = Offset(startX, centerY + 26.dp.toPx()),
                strokeWidth = 5.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = handleColor,
                radius = 7.dp.toPx(),
                center = Offset(startX, centerY),
            )
            drawLine(
                color = handleColor,
                start = Offset(endX, centerY - 26.dp.toPx()),
                end = Offset(endX, centerY + 26.dp.toPx()),
                strokeWidth = 5.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = handleColor,
                radius = 7.dp.toPx(),
                center = Offset(endX, centerY),
            )
            drawLine(
                color = playheadColor,
                start = Offset(playX, centerY - 19.dp.toPx()),
                end = Offset(playX, centerY + 19.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatTrimTime(trimStartMs), style = MaterialTheme.typography.labelMedium)
            Text(
                formatTrimTime(positionMs.coerceIn(trimStartMs, trimEndMs)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(formatTrimTime(trimEndMs), style = MaterialTheme.typography.labelMedium)
        }

        IconButton(
            onClick = {
                if (player.isPlaying) {
                    player.pause()
                } else {
                    val end = trimEndMs.coerceAtLeast(trimStartMs + 1L)
                    val current = player.currentPosition
                    if (current < trimStartMs || current >= end - 40L) {
                        player.seekTo(trimStartMs)
                        positionMs = trimStartMs
                    }
                    player.play()
                }
            },
            modifier = Modifier.padding(top = 2.dp),
        ) {
            Icon(
                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Пауза" else "Предпросмотр",
            )
        }
    }
}

fun formatPreciseMediaTime(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val totalSeconds = safe / 1_000L
    val centiseconds = (safe % 1_000L) / 10L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d.%02d".format(hours, minutes, seconds, centiseconds)
    } else {
        "%d:%02d.%02d".format(minutes, seconds, centiseconds)
    }
}

private fun formatTrimTime(ms: Long): String = formatPreciseMediaTime(ms)

private const val MIN_TRIM_MS = 100L
private const val SNAP_EDGE_MS = 120L

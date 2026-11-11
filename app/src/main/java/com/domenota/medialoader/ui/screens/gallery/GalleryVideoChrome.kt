package com.domenota.medialoader.ui.screens.gallery

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Gallery-only Compose controller for Media3 playback.
 * Media3's own PlayerView controller stays disabled in the gallery: no
 * duplicate toolbar, no measured native button coordinates, no media resizes.
 * The GalleryMediaViewer owns the common top/action bars.
 */
@Composable
internal fun GalleryVideoChrome(
    visible: Boolean,
    locked: Boolean,
    unlockVisible: Boolean,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onLockChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    Box(modifier.fillMaxSize()) {
        if (!locked && visible) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(if (landscape) 20.dp else 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.SkipPrevious,
                        contentDescription = if (hasPrevious) "К началу или предыдущему видео" else "К началу видео",
                        tint = Color.White, modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = onPlayPause, modifier = Modifier.size(64.dp)) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playing) "Пауза" else "Воспроизвести",
                        tint = Color.White, modifier = Modifier.size(43.dp))
                }
                IconButton(onClick = onNext, enabled = hasNext, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Следующее видео",
                        tint = Color.White.copy(alpha = if (hasNext) 1f else .35f),
                        modifier = Modifier.size(30.dp))
                }
            }

            // One slim, contiguous timeline; no Material3 track gap or thick pill.
            // Keep a 40dp scrubbing target, independent of its 4dp visual stroke.
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = if (landscape) 8.dp else 86.dp)
                    .background(Brush.verticalGradient(listOf(
                        Color.Transparent, Color.Black.copy(alpha = .26f))))
                    .height(48.dp)
                    .padding(horizontal = if (landscape) 24.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val length = durationMs.coerceAtLeast(1L)
                Text(galleryVideoClock(scrubbing?.toLong() ?: positionMs),
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                // Render thumb and track in ONE Canvas coordinate system.
                // This prevents any vertical offset or Material3-specific gap.
                val visiblePosition = (scrubbing ?: positionMs.toFloat())
                    .coerceIn(0f, length.toFloat())
                Canvas(
                    Modifier.weight(1f).height(40.dp)
                        .pointerInput(length) {
                            detectTapGestures { point ->
                                val fraction = galleryTimelineFraction(
                                    point.x, size.width.toFloat(), 6.dp.toPx())
                                onSeekTo((fraction * length).toLong())
                            }
                        }
                        .pointerInput(length) {
                            detectDragGestures(
                                onDragStart = { point ->
                                    scrubbing = galleryTimelineFraction(
                                        point.x, size.width.toFloat(), 6.dp.toPx()) * length
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    scrubbing = galleryTimelineFraction(
                                        change.position.x, size.width.toFloat(), 6.dp.toPx()) * length
                                },
                                onDragEnd = {
                                    scrubbing?.let { onSeekTo(it.toLong()) }
                                    scrubbing = null
                                },
                                onDragCancel = { scrubbing = null },
                            )
                        }
                        .semantics {
                            contentDescription = "Положение воспроизведения"
                            progressBarRangeInfo = ProgressBarRangeInfo(
                                visiblePosition, 0f..length.toFloat())
                            setProgress { newValue ->
                                onSeekTo(newValue.toLong().coerceIn(0L, length))
                                true
                            }
                        },
                ) {
                    val centerY = size.height / 2f
                    val radius = 6.dp.toPx()
                    val startX = radius
                    val endX = (size.width - radius).coerceAtLeast(radius)
                    val thumbX = galleryTimelineThumbX(
                        visiblePosition / length.toFloat(), size.width, radius)
                    drawLine(Color(0xFF858585).copy(alpha = .80f),
                        Offset(startX, centerY), Offset(endX, centerY),
                        strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(Color.White,
                        Offset(startX, centerY), Offset(thumbX, centerY),
                        strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                    drawCircle(Color.White, radius = radius, center = Offset(thumbX, centerY))
                }
                Text(galleryVideoClock(durationMs), color = Color.White, fontSize = 12.sp,
                    fontWeight = FontWeight.Medium)
            }
        }
        // Single stable right-side anchor for BOTH lock states. Horizontal safe
        // insets avoid the old lock drift when portrait chrome/nav bars hide.
        if ((!locked && visible) || (locked && unlockVisible)) {
            Box(
                Modifier.align(Alignment.CenterEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(end = 16.dp),
            ) {
                IconButton(
                    onClick = { onLockChange(!locked) },
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                        contentDescription = if (locked) "Разблокировать управление"
                            else "Заблокировать управление",
                        modifier = Modifier.size(27.dp),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

internal fun galleryVideoClock(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(
        seconds / 3600, (seconds / 60) % 60, seconds % 60
    ) else "%02d:%02d".format(seconds / 60, seconds % 60)
}

/** One coordinate system for thumb and track, safe even on narrow viewports. */
internal fun galleryTimelineThumbX(fraction: Float, width: Float, radius: Float): Float {
    val start = radius.coerceAtLeast(0f)
    val length = (width - 2f * start).coerceAtLeast(0f)
    return start + fraction.coerceIn(0f, 1f) * length
}

internal fun galleryTimelineFraction(x: Float, width: Float, radius: Float): Float {
    val start = radius.coerceAtLeast(0f)
    val travel = (width - 2f * start).coerceAtLeast(1f)
    return ((x - start) / travel).coerceIn(0f, 1f)
}

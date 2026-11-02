package com.domenota.medialoader.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.domenota.medialoader.data.editor.VideoClipDraft
import com.domenota.medialoader.data.editor.VideoClipMediaKind
import com.domenota.medialoader.data.editor.VideoProjectDraft
import com.domenota.medialoader.ui.components.formatPreciseMediaTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.roundToInt

private val selectedYellow = Color(0xFFFFC529)

@Composable
internal fun VideoMultiClipTimeline(
    project: VideoProjectDraft,
    selectedClipId: String,
    positionMs: Long,
    isPlaying: Boolean,
    enabled: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    frameIntervalMs: Long?,
    onFrameStep: (Int) -> Unit,
    onSelect: (String) -> Unit,
    onSeek: (Long) -> Unit,
    onTrim: (String, Long, Long) -> Unit,
    onMove: (String, Int) -> Unit,
    onAdd: () -> Unit,
    onSelectTransition: (String) -> Unit = {},
    onTogglePlayback: () -> Unit,
) {
    // Full timeline detail by default; the scale is not user-adjustable.
    val zoom = 2.5f
    val density = LocalDensity.current.density
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = onTogglePlayback, enabled = enabled, modifier = Modifier.size(38.dp)) {
                Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Пауза" else "Воспроизвести")
            }
            Text(formatPreciseMediaTime(positionMs) + " / " +
                formatPreciseMediaTime(project.durationMs),
                style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = onUndo, enabled = enabled && canUndo,
                    modifier = Modifier.size(30.dp)) {
                    Icon(VideoV8Icons.undo, contentDescription = "Отменить действие",
                        modifier = Modifier.size(21.dp))
                }
                IconButton(onClick = onRedo, enabled = enabled && canRedo,
                    modifier = Modifier.size(30.dp)) {
                    Icon(VideoV8Icons.redo, contentDescription = "Повторить действие",
                        modifier = Modifier.size(21.dp))
                }
            }
            if (frameIntervalMs != null && frameIntervalMs > 0L) {
                IconButton(onClick = { onFrameStep(-1) }, enabled = enabled,
                    modifier = Modifier.size(29.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Предыдущий кадр")
                }
                IconButton(onClick = { onFrameStep(1) }, enabled = enabled,
                    modifier = Modifier.size(29.dp)) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Следующий кадр")
                }
            }
        }
        LazyRow(Modifier.fillMaxWidth().height(77.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically) {
            itemsIndexed(project.clips, key = { _, clip -> clip.id }) { index, clip ->
                    val width = VideoClipReorderMath.widthDp(clip.durationMs, zoom).dp
                    VideoClipStrip(
                        clip = clip,
                        widthModifier = Modifier.width(width),
                        isSelected = clip.id == selectedClipId,
                        localPositionMs = (positionMs - project.timelineStart(index))
                            .takeIf { it >= 0L && it <= clip.durationMs },
                        enabled = enabled,
                        onSelectTransition = if (index < project.clips.lastIndex)
                            ({ onSelectTransition(clip.id) }) else null,
                        transitionEnabled = clip.transitionOut.enabled &&
                            index < project.clips.lastIndex,
                        onSelect = { onSelect(clip.id) },
                        onSeek = { fraction ->
                            onSelect(clip.id)
                            onSeek(project.timelineStart(index) +
                                (clip.durationMs * fraction).roundToLong())
                        },
                        onTrim = { a, b -> onTrim(clip.id, a, b) },
                        onDragOrder = { deltaPx ->
                            val destination = VideoClipReorderMath.destinationIndex(
                                project.clips.map { it.durationMs },
                                index, deltaPx, zoom, density,
                            )
                            if (destination != index) onMove(clip.id, destination)
                        }
                    )
            }
            item {
                IconButton(onClick = onAdd, enabled = enabled) {
                    Icon(Icons.Rounded.Add, contentDescription = "Добавить клипы")
                }
            }
        }
    }
}

@Composable
private fun VideoClipStrip(
    clip: VideoClipDraft,
    widthModifier: Modifier,
    isSelected: Boolean,
    localPositionMs: Long?,
    enabled: Boolean,
    onSelectTransition: (() -> Unit)?,
    transitionEnabled: Boolean,
    onSelect: () -> Unit,
    onSeek: (Float) -> Unit,
    onTrim: (Long, Long) -> Unit,
    onDragOrder: (Float) -> Unit,
) {
    val context = LocalContext.current
    val latestClip by rememberUpdatedState(clip)
    val latestOnTrim by rememberUpdatedState(onTrim)
    // Do not re-decode 4 full-resolution frames on EVERY millisecond of trim drag.
    // The player independently seeks to the exact active boundary every drag event.
    val thumbnailStartSecond = clip.inMs / 1000L
    val thumbnailEndSecond = clip.outMs / 1000L
    var thumbnails by remember(clip.sourceUri, thumbnailStartSecond, thumbnailEndSecond) {
        mutableStateOf<List<Bitmap>>(emptyList())
    }
    LaunchedEffect(clip.sourceUri, thumbnailStartSecond, thumbnailEndSecond) {
        val generated = withContext(Dispatchers.IO) {
            if (clip.mediaKind == VideoClipMediaKind.IMAGE) {
                runCatching {
                    val uri = Uri.parse(clip.sourceUri)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    var sample = 1
                    while (bounds.outWidth / sample > 350 ||
                        bounds.outHeight / sample > 350) sample *= 2
                    val small = context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null,
                            BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                    if (small == null) emptyList()
                    else listOf(small)
                }.getOrDefault(emptyList())
            } else {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, Uri.parse(clip.sourceUri))
                    (0..3).mapNotNull { step ->
                        val ms = clip.inMs + (clip.sourceSpanMs - 1L)
                            .coerceAtLeast(0L) * step / 3
                        // Prefer decoder-side scaled frames; only fall back to
                        // full-frame extraction on Android API 26.
                        val bmp = if (Build.VERSION.SDK_INT >= 27)
                            retriever.getScaledFrameAtTime(ms * 1000L,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 150, 100)
                        else retriever.getFrameAtTime(ms * 1000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        bmp?.let {
                            val w = (it.width * (100f / it.height.coerceAtLeast(1)))
                                .toInt().coerceIn(1, 150)
                            val small = Bitmap.createScaledBitmap(it, w, 100, true)
                            if (small !== it) it.recycle()
                            small
                        }
                    }
                } catch (_: Exception) { emptyList() }
                finally { retriever.release() }
            }
        }
        thumbnails = generated
    }
    DisposableEffect(thumbnails) {
        val owned = thumbnails
        onDispose { owned.forEach { if (!it.isRecycled) it.recycle() } }
    }
    var pixelWidth by remember { mutableFloatStateOf(1f) }
    var longPressDelta by remember { mutableFloatStateOf(0f) }
    var dragging by remember(clip.id) { mutableStateOf(false) }
    Box(widthModifier.height(67.dp)
        .offset { IntOffset(longPressDelta.roundToInt(), 0) }
        .zIndex(if (dragging) 2f else 0f)
        .clip(RoundedCornerShape(9.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .border(if (isSelected) 3.dp else 1.dp,
            if (isSelected) selectedYellow else MaterialTheme.colorScheme.outlineVariant,
            RoundedCornerShape(9.dp))) {
        Row(Modifier.fillMaxSize()
            .onSizeChanged { pixelWidth = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(clip.id, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onTap = { point ->
                    onSelect()
                    onSeek((point.x / pixelWidth).coerceIn(0f, 1f))
                })
            }
            .pointerInput(clip.id, clip.inMs, clip.outMs, enabled) {
                if (!enabled) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { longPressDelta = 0f; dragging = true },
                    onDrag = { change, drag ->
                        change.consume()
                        longPressDelta += drag.x
                    },
                    onDragEnd = {
                        val draggedPx = longPressDelta
                        longPressDelta = 0f
                        dragging = false
                        if (abs(draggedPx) > 25f) onDragOrder(draggedPx)
                    },
                    onDragCancel = { longPressDelta = 0f; dragging = false },
                )
            }, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            if (thumbnails.isEmpty()) Box(Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center) {
                Text("Видео", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                thumbnails.forEach { bmp ->
                    Image(bmp.asImageBitmap(), contentDescription = null,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentScale = ContentScale.Crop)
                }
            }
        }
        if (isSelected) {
            // Trim handles operate directly on original clip source coordinates.
            Box(Modifier.align(Alignment.CenterStart).width(17.dp).fillMaxHeight()
                .background(selectedYellow.copy(alpha = .85f))
                .pointerInput(clip.id, enabled) {
                    if (!enabled || latestClip.mediaKind == VideoClipMediaKind.IMAGE ||
                        latestClip.sourceSpanMs < 200L) return@pointerInput
                    detectDragGestures { change, delta ->
                        change.consume()
                        val shift = (delta.x / pixelWidth * latestClip.sourceSpanMs).roundToLong()
                        val limit = (latestClip.outMs - 100L).coerceAtLeast(0L)
                        latestOnTrim((latestClip.inMs + shift).coerceIn(0L, limit), latestClip.outMs)
                    }
                })
            Box(Modifier.align(Alignment.CenterEnd).width(17.dp).fillMaxHeight()
                .background(selectedYellow.copy(alpha = .85f))
                .pointerInput(clip.id, enabled) {
                    if (!enabled || latestClip.mediaKind == VideoClipMediaKind.IMAGE ||
                        latestClip.sourceSpanMs < 200L) return@pointerInput
                    detectDragGestures { change, delta ->
                        change.consume()
                        val shift = (delta.x / pixelWidth * latestClip.sourceSpanMs).roundToLong()
                        val minEnd = (latestClip.inMs + 100L).coerceAtMost(latestClip.sourceDurationMs)
                        latestOnTrim(latestClip.inMs, (latestClip.outMs + shift)
                            .coerceIn(minEnd, latestClip.sourceDurationMs))
                    }
                })
        }
        if (onSelectTransition != null) {
            Box(Modifier.align(Alignment.TopEnd).size(28.dp)
                .clip(RoundedCornerShape(bottomStart = 8.dp))
                .background(if (transitionEnabled) selectedYellow else Color.Black.copy(alpha = .65f))
                .clickable(enabled = enabled, onClick = onSelectTransition),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.AutoAwesome,
                    contentDescription = "Переход после клипа",
                    modifier = Modifier.size(16.dp),
                    tint = if (transitionEnabled) Color.Black else Color.White)
            }
        }
        if (localPositionMs != null) {
            Canvas(Modifier.fillMaxSize()) {
                val x = localPositionMs.toFloat() / clip.durationMs * size.width
                drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
            }
        }
        Text(formatPreciseMediaTime(clip.durationMs),
            color = Color.White, fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = .65f)).padding(horizontal = 4.dp))
    }
}

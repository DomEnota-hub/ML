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
import androidx.compose.ui.platform.testTag
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
    trimMode: Boolean = false,
    onTrimGestureStart: () -> Unit = {},
    onTrimGestureEnd: () -> Unit = {},
    onMove: (String, Int) -> Unit,
    onAdd: () -> Unit,
    onSelectTransition: (String) -> Unit = {},
    onTogglePlayback: () -> Unit,
    showFrameSteps: Boolean = true,
    selectedAudioTrackId: String? = null,
    onSelectAudio: (String) -> Unit = {},
    onChangeAudio: (com.domenota.medialoader.data.editor.VideoAudioTrackDraft) -> Unit = {},
    texts: List<com.domenota.medialoader.data.editor.TextLayer> = emptyList(),
    selectedTextId: String? = null,
    onSelectText: (String) -> Unit = {},
    onChangeText: (com.domenota.medialoader.data.editor.TextLayer) -> Unit = {},
    onTextGestureStart: () -> Unit = {}, onTextGestureEnd: () -> Unit = {},
) {
    // Full timeline detail by default; the scale is not user-adjustable.
    val zoom = 2.5f
    // The scale is calculated from the ORIGINAL (untrimmed) source length so
    // yellow-handle trimming does not alter px/sec during a single gesture.
    val referenceDurationMs = project.clips.maxOf {
        (it.sourceDurationMs / it.speed).roundToLong().coerceAtLeast(1L)
    }
    val pixelsPerSecondDp = VideoClipReorderMath.pixelsPerSecondDp(
        zoom, referenceDurationMs)
    val videoScroll = androidx.compose.foundation.lazy.rememberLazyListState()
    val density = LocalDensity.current.density
    androidx.compose.runtime.LaunchedEffect(selectedTextId, trimMode) {
        val text = texts.firstOrNull { it.id == selectedTextId } ?: return@LaunchedEffect
        if (trimMode) return@LaunchedEffect
        val location = project.locateAt(text.startMs.coerceAtMost(project.durationMs - 1))
        val clip = project.clips[location.clipIndex]
        val width = VideoClipReorderMath.widthDp(clip.durationMs, zoom, referenceDurationMs)
        val viewport = (videoScroll.layoutInfo.viewportEndOffset - videoScroll.layoutInfo.viewportStartOffset).coerceAtLeast(0)
        val offset = (width * density * location.positionWithinClipMs / clip.durationMs - viewport / 2f)
            .roundToInt().coerceAtLeast(0)
        videoScroll.scrollToItem(location.clipIndex, offset)
    }
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
            if (showFrameSteps && frameIntervalMs != null && frameIntervalMs > 0L) {
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
        LazyRow(Modifier.fillMaxWidth().height(77.dp), state = videoScroll,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically) {
            itemsIndexed(project.clips, key = { _, clip -> clip.id }) { index, clip ->
                    val sourceTrimMode = trimMode && clip.id == selectedClipId &&
                        clip.mediaKind == VideoClipMediaKind.VIDEO
                    val displayDuration = if (sourceTrimMode)
                        (clip.sourceDurationMs / clip.speed).roundToLong()
                    else clip.durationMs
                    val width = VideoClipReorderMath.widthDp(
                        displayDuration, zoom, referenceDurationMs)
                    VideoClipStrip(
                        clip = clip,
                        widthModifier = Modifier.width(width.dp),
                        showSourceTrim = sourceTrimMode,
                        fullWidthDp = width,
                        pixelsPerSecondDp = if (sourceTrimMode)
                            width / (clip.sourceDurationMs / clip.speed / 1000f)
                        else pixelsPerSecondDp,
                        displayDensity = density,
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
                        onTrimGestureStart = onTrimGestureStart,
                        onTrimGestureEnd = onTrimGestureEnd,
                        onDragOrder = { deltaPx ->
                            val destination = VideoClipReorderMath.destinationIndex(
                                project.clips.map { it.durationMs },
                                index, deltaPx, zoom, density,
                                referenceDurationMs = referenceDurationMs,
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
        if (!trimMode && texts.isNotEmpty()) VideoTextLanes(project, texts, selectedTextId,
            positionMs, videoScroll, project.clips.map { VideoClipReorderMath.widthDp(it.durationMs, zoom, referenceDurationMs) },
            enabled, onSelectText, onChangeText, onTextGestureStart, onTextGestureEnd)
            if (!trimMode && project.audioTracks.isNotEmpty()) VideoAudioLanes(project,
            selectedAudioTrackId, positionMs, videoScroll, project.clips.map {
                VideoClipReorderMath.widthDp(it.durationMs, zoom, referenceDurationMs) },
            enabled, onSelectAudio, onChangeAudio, onTrimGestureStart, onTrimGestureEnd)
    }

}

@Composable
private fun VideoClipStrip(
    clip: VideoClipDraft,
    widthModifier: Modifier,
    showSourceTrim: Boolean,
    fullWidthDp: Float,
    pixelsPerSecondDp: Float,
    displayDensity: Float,
    isSelected: Boolean,
    localPositionMs: Long?,
    enabled: Boolean,
    onSelectTransition: (() -> Unit)?,
    transitionEnabled: Boolean,
    onSelect: () -> Unit,
    onSeek: (Float) -> Unit,
    onTrim: (Long, Long) -> Unit,
    onTrimGestureStart: () -> Unit,
    onTrimGestureEnd: () -> Unit,
    onDragOrder: (Float) -> Unit,
) {
    val context = LocalContext.current
    val latestClip by rememberUpdatedState(clip)
    val latestOnTrim by rememberUpdatedState(onTrim)
    val latestOnTrimGestureStart by rememberUpdatedState(onTrimGestureStart)
    val latestOnTrimGestureEnd by rememberUpdatedState(onTrimGestureEnd)
    // Do not re-decode 4 full-resolution frames on EVERY millisecond of trim drag.
    // The player independently seeks to the exact active boundary every drag event.
    val thumbnailStartSecond = if (showSourceTrim) 0L else clip.inMs / 1000L
    val thumbnailEndSecond = if (showSourceTrim)
        clip.sourceDurationMs / 1000L else clip.outMs / 1000L
    var thumbnails by remember(clip.sourceUri, thumbnailStartSecond, thumbnailEndSecond) {
        mutableStateOf<List<Bitmap>>(emptyList())
    }
    LaunchedEffect(clip.sourceUri, thumbnailStartSecond, thumbnailEndSecond,
        showSourceTrim) {
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
                        val firstMs = if (showSourceTrim) 0L else clip.inMs
                        val spanMs = if (showSourceTrim)
                            clip.sourceDurationMs else clip.sourceSpanMs
                        val ms = firstMs + (spanMs - 1L)
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
        .border(if (isSelected && !showSourceTrim) 3.dp else 1.dp,
            if (isSelected && !showSourceTrim) selectedYellow
                else MaterialTheme.colorScheme.outlineVariant,
            RoundedCornerShape(9.dp))) {
        Row(Modifier.fillMaxSize()
            .onSizeChanged { pixelWidth = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(clip.id, enabled, showSourceTrim) {
                if (!enabled) return@pointerInput
                detectTapGestures(onTap = { point ->
                    onSelect()
                    val tappedFraction = (point.x / pixelWidth).coerceIn(0f, 1f)
                    val clipFraction = if (showSourceTrim) {
                        val sourceAtTap = (tappedFraction * latestClip.sourceDurationMs)
                            .toLong().coerceIn(latestClip.inMs, latestClip.outMs)
                        (sourceAtTap - latestClip.inMs).toFloat() /
                            latestClip.sourceSpanMs.coerceAtLeast(1L)
                    } else tappedFraction
                    onSeek(clipFraction.coerceIn(0f, 1f))
                })
            }
            .pointerInput(clip.id, enabled) {
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
            // Independent, stable source-bound trim handles. Pixels map to
            // TIME on a fixed scale (not to the ever-changing current width).
            // Each gesture captures its original bounds and cumulative movement.
            // One finger drag therefore shortens/extends the clip proportionally.
            // In trim mode the complete SOURCE is the ruler. The yellow kept
            // interval contracts/expands between two moving handles, while the
            // discarded source is visibly dimmed. Project duration updates live.
            val visualSelection = if (showSourceTrim)
                VideoClipReorderMath.trimSelection(
                    clip.sourceDurationMs, clip.inMs, clip.outMs, fullWidthDp)
            else VideoClipReorderMath.TrimSelection(0f, fullWidthDp)
            val leftDp = visualSelection.leftDp
            val rightDp = visualSelection.rightDp
            val keptDp = visualSelection.keptDp
            val grips = VideoClipReorderMath.trimGrips(visualSelection, fullWidthDp)
            if (showSourceTrim) {
                Box(Modifier.align(Alignment.CenterStart).width(leftDp.dp)
                    .fillMaxHeight().background(Color.Black.copy(alpha = .72f)))
                Box(Modifier.align(Alignment.CenterEnd)
                    .width((fullWidthDp - rightDp).coerceAtLeast(0f).dp)
                    .fillMaxHeight().background(Color.Black.copy(alpha = .72f)))
                Box(Modifier.align(Alignment.CenterStart)
                    .offset(x = leftDp.dp).testTag("trim-kept-${clip.id}").width(keptDp.dp).fillMaxHeight()
                    .border(2.dp, selectedYellow))
            }
            Box(Modifier.align(Alignment.CenterStart)
                .offset(x = grips.leftDp.dp).testTag("trim-start-${clip.id}")
                .width(17.dp).fillMaxHeight()
                .background(selectedYellow.copy(alpha = .93f))
                .pointerInput(clip.id, enabled, pixelsPerSecondDp, displayDensity) {
                    if (!enabled || latestClip.mediaKind == VideoClipMediaKind.IMAGE)
                        return@pointerInput
                    var initialIn = 0L
                    var initialOut = 0L
                    var cumulativePx = 0f
                    var sourceSpeed = 1f
                    detectDragGestures(
                        onDragStart = {
                            initialIn = latestClip.inMs
                            initialOut = latestClip.outMs
                            sourceSpeed = latestClip.speed
                            cumulativePx = 0f
                            latestOnTrimGestureStart()
                        },
                        onDragEnd = { latestOnTrimGestureEnd() },
                        onDragCancel = { latestOnTrimGestureEnd() },
                        onDrag = { change, delta ->
                            change.consume()
                            cumulativePx += delta.x
                            val shift = VideoClipReorderMath.sourceDeltaMs(
                                cumulativePx, pixelsPerSecondDp, displayDensity, sourceSpeed)
                            latestOnTrim((initialIn + shift)
                                .coerceIn(0L, initialOut - 100L), initialOut)
                        },
                    )
                })
            Box(Modifier.align(Alignment.CenterStart)
                .offset(x = grips.rightDp.dp).testTag("trim-end-${clip.id}")
                .width(17.dp).fillMaxHeight()
                .background(selectedYellow.copy(alpha = .93f))
                .pointerInput(clip.id, enabled, pixelsPerSecondDp, displayDensity) {
                    if (!enabled || latestClip.mediaKind == VideoClipMediaKind.IMAGE)
                        return@pointerInput
                    var initialIn = 0L
                    var initialOut = 0L
                    var sourceDuration = 0L
                    var sourceSpeed = 1f
                    var cumulativePx = 0f
                    detectDragGestures(
                        onDragStart = {
                            initialIn = latestClip.inMs
                            initialOut = latestClip.outMs
                            sourceDuration = latestClip.sourceDurationMs
                            sourceSpeed = latestClip.speed
                            cumulativePx = 0f
                            latestOnTrimGestureStart()
                        },
                        onDragEnd = { latestOnTrimGestureEnd() },
                        onDragCancel = { latestOnTrimGestureEnd() },
                        onDrag = { change, delta ->
                            change.consume()
                            cumulativePx += delta.x
                            val shift = VideoClipReorderMath.sourceDeltaMs(
                                cumulativePx, pixelsPerSecondDp, displayDensity, sourceSpeed)
                            latestOnTrim(initialIn, (initialOut + shift)
                                .coerceIn(initialIn + 100L, sourceDuration))
                        },
                    )
                })
        }
        // Do not let the transition corner button intercept the right trim
        // grip. The transition remains available in the common editor dock.
        if (onSelectTransition != null && !showSourceTrim) {
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
                val x = if (showSourceTrim)
                    clip.sourceAt(localPositionMs).toFloat() /
                        clip.sourceDurationMs * size.width
                else localPositionMs.toFloat() / clip.durationMs * size.width
                drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
            }
        }
        // Always show remaining duration and removed time, even at narrow widths.
        if (showSourceTrim) {
            val removedOutput = ((clip.sourceDurationMs - clip.sourceSpanMs) /
                clip.speed).roundToLong().coerceAtLeast(0L)
            Text("Осталось ${formatPreciseMediaTime(clip.durationMs)}" +
                    if (removedOutput > 0L)
                        "  −${formatPreciseMediaTime(removedOutput)}" else "",
                color = Color.White, fontSize = 11.sp, maxLines = 1,
                modifier = Modifier.align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = .86f))
                    .padding(horizontal = 5.dp, vertical = 3.dp))
        } else {
            Text(formatPreciseMediaTime(clip.durationMs),
                color = Color.White, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = .65f))
                    .padding(horizontal = 4.dp))
        }
    }
}

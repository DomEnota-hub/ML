package com.domenota.medialoader.ui.screens

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.data.editor.*
import com.domenota.medialoader.data.playback.AudioWaveformPrecompute
import com.domenota.medialoader.ui.components.readCachedAudioWaveform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Cached real PCM envelope; video lazy-row decoding/trim gesture implementation is untouched. */
@Composable
internal fun VideoAudioLanes(project: VideoProjectDraft, selectedId: String?,
    videoScroll: LazyListState, widths: List<Float>, enabled: Boolean,
    onSelect: (String) -> Unit, onChange: (VideoAudioTrackDraft) -> Unit,
    onGestureStart: () -> Unit, onGestureEnd: () -> Unit, leadingPaddingDp: Float = 0f) {
    val scale = remember(project.clips, widths) { VideoAudioLaneScale(project, widths) }
    val density = LocalDensity.current.density
    val scrollDp = { scale.prefix(videoScroll.firstVisibleItemIndex) + videoScroll.firstVisibleItemScrollOffset / density - leadingPaddingDp }
    val tracks = project.visibleAudioTracks()
    LazyColumn(Modifier.fillMaxWidth().height((tracks.size.coerceAtMost(2) * 42).dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(tracks, key = { it.id }) { track ->
            VideoAudioLane(track, track.id == selectedId, scale, scrollDp, enabled,
                project.durationMs, onSelect, onChange, onGestureStart, onGestureEnd)
        }
    }
}

@Composable
private fun VideoAudioLane(track: VideoAudioTrackDraft, selected: Boolean,
    scale: VideoAudioLaneScale, scrollDp: () -> Float, enabled: Boolean,
    projectDurationMs: Long, onSelect: (String) -> Unit, onChange: (VideoAudioTrackDraft) -> Unit,
    onGestureStart: () -> Unit, onGestureEnd: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    var peaks by remember(track.sourceUri, track.sourceDurationMs) { mutableStateOf(emptyList<Float>()) }
    LaunchedEffect(track.sourceUri, track.sourceDurationMs) {
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        try {
            peaks = withContext(Dispatchers.IO) {
                val uri = Uri.parse(track.sourceUri)
                readCachedAudioWaveform(context, uri, track.sourceDurationMs)
                    ?: com.domenota.medialoader.ui.components.loadAudioWaveform(context, uri,
                        track.sourceDurationMs, isCancelled = { job?.isActive == false })
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            com.domenota.medialoader.core.logging.AppLog.w("AudioWaveform", "Audio lane waveform failed", error)
        }
    }
    var dragging by remember(track.id) { mutableStateOf(false) }
    val latestTrack by rememberUpdatedState(track)
    val latestScale by rememberUpdatedState(scale)
    val latestDuration by rememberUpdatedState(projectDurationMs)
    val latestChange by rememberUpdatedState(onChange)
    val latestSelect by rememberUpdatedState(onSelect)
    val latestStart by rememberUpdatedState(onGestureStart)
    val latestEnd by rememberUpdatedState(onGestureEnd)
    val handleCoordinates = remember { mutableMapOf<VideoAudioLaneHandle, LayoutCoordinates>() }
    fun Modifier.drag(handle: VideoAudioLaneHandle) = onGloballyPositioned { handleCoordinates[handle] = it }.pointerInput(track.id, handle, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            val initial = latestTrack
            val gestureScale = latestScale
            val startPointer = handleCoordinates[handle]?.localToRoot(down.position)?.x ?: down.position.x
            var delta = 0f
            dragging = true; latestSelect(initial.id); latestStart()
            try {
                do {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    delta = ((handleCoordinates[handle]?.localToRoot(change.position)?.x ?: change.position.x) - startPointer) / density
                    change.consume()
                    val originalTime = if (handle == VideoAudioLaneHandle.END)
                        initial.atMs + initial.durationMs else initial.atMs
                    val target = gestureScale.timeAt(gestureScale.xAt(originalTime) + delta)
                    val updated = VideoAudioLaneEdit.apply(initial, handle, target, latestDuration)
                    if (updated != latestTrack) latestChange(updated)
                } while (change.pressed)
            } finally { dragging = false; latestEnd() }
        }
    }

    val start = scale.xAt(track.atMs)
    val end = scale.xAt(track.atMs + track.durationMs)
    val width = (end - start).coerceAtLeast(1f)
    val handles = selected && (width >= 40f || dragging)
    Box(Modifier.fillMaxWidth().height(38.dp).clipToBounds()) {
        Box(Modifier.offset { IntOffset(((start - scrollDp()) * density).roundToInt(), 0) }
            .wrapContentWidth(Alignment.Start, unbounded = true)
            .width(width.dp).testTag("audio-lane-${track.id}").fillMaxHeight().clip(RoundedCornerShape(7.dp))
            .background(Color(0xFFAD37BE)).border(if (selected) 3.dp else 1.dp,
                Color(0xFFDF7CF4), RoundedCornerShape(7.dp))) {
            Canvas(Modifier.fillMaxSize()) {
                val count = peaks.size
                peaks.forEachIndexed { index, amplitude ->
                    val sourceMs = (index + .5f) / count * track.sourceDurationMs
                    if (sourceMs >= track.inMs && sourceMs < track.outMs) {
                        val time = track.atMs + ((sourceMs - track.inMs) / track.speed).toLong()
                        val x = (scale.xAt(time) - start) * density
                        val half = amplitude.coerceIn(0f, 1f) * track.gainAt(time) * size.height * .45f
                        drawLine(Color(0xFFDE85ED), Offset(x, size.height / 2 - half),
                            Offset(x, size.height / 2 + half), 1.dp.toPx())
                    }
                }
            }
            Text(track.title ?: "Аудиодорожка", color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
            Box(Modifier.fillMaxSize().padding(horizontal = if (handles) 17.dp else 0.dp)
                .pointerInput(track.id) { detectTapGestures { if (enabled) latestSelect(track.id) } }
                .drag(VideoAudioLaneHandle.MOVE))
            if (handles) {
                Box(Modifier.align(Alignment.CenterStart).testTag("audio-start-${track.id}").width(17.dp).fillMaxHeight().drag(VideoAudioLaneHandle.START),
                    contentAlignment = Alignment.Center) { Box(Modifier.width(2.dp).height(15.dp).background(Color(0xFF54205E))) }
                Box(Modifier.align(Alignment.CenterEnd).testTag("audio-end-${track.id}").width(17.dp).fillMaxHeight().drag(VideoAudioLaneHandle.END),
                    contentAlignment = Alignment.Center) { Box(Modifier.width(2.dp).height(15.dp).background(Color(0xFF54205E))) }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val x = size.width / 2f
            if (x in 0f..size.width) drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
        }
    }
}

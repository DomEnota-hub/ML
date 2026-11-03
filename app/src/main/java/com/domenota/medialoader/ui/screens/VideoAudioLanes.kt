package com.domenota.medialoader.ui.screens

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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
internal fun VideoAudioLanes(project: VideoProjectDraft, selectedId: String?, positionMs: Long,
    videoScroll: LazyListState, widths: List<Float>, enabled: Boolean,
    onSelect: (String) -> Unit, onChange: (VideoAudioTrackDraft) -> Unit,
    onGestureStart: () -> Unit, onGestureEnd: () -> Unit) {
    val scale = remember(project.clips, widths) { VideoAudioLaneScale(project, widths) }
    val density = LocalDensity.current.density
    val scrollDp = scale.prefix(videoScroll.firstVisibleItemIndex) + videoScroll.firstVisibleItemScrollOffset / density
    val tracks = project.visibleAudioTracks()
    LazyColumn(Modifier.fillMaxWidth().height((tracks.size.coerceAtMost(2) * 42).dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(tracks, key = { it.id }) { track ->
            VideoAudioLane(track, track.id == selectedId, scale, scrollDp, positionMs, enabled,
                project.durationMs, onSelect, onChange, onGestureStart, onGestureEnd)
        }
    }
}

@Composable
private fun VideoAudioLane(track: VideoAudioTrackDraft, selected: Boolean,
    scale: VideoAudioLaneScale, scrollDp: Float, positionMs: Long, enabled: Boolean,
    projectDurationMs: Long, onSelect: (String) -> Unit, onChange: (VideoAudioTrackDraft) -> Unit,
    onGestureStart: () -> Unit, onGestureEnd: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    var peaks by remember(track.sourceUri, track.sourceDurationMs) { mutableStateOf(emptyList<Float>()) }
    LaunchedEffect(track.sourceUri, track.sourceDurationMs) {
        suspend fun cached() = withContext(Dispatchers.IO) { readCachedAudioWaveform(context,
            Uri.parse(track.sourceUri), track.sourceDurationMs) }
        val ready = cached()
        peaks = ready.orEmpty()
        if (ready == null) {
            AudioWaveformPrecompute.requestImmediate(context, Uri.parse(track.sourceUri), track.sourceDurationMs)
            repeat(240) {
                delay(1000)
                val loaded = cached()
                if (loaded != null) {
                    peaks = loaded
                    return@LaunchedEffect
                }
            }
        }
    }
    var dragging by remember(track.id) { mutableStateOf(false) }
    val latestTrack by rememberUpdatedState(track)
    val latestScale by rememberUpdatedState(scale)
    val latestChange by rememberUpdatedState(onChange)
    val latestSelect by rememberUpdatedState(onSelect)
    val latestStart by rememberUpdatedState(onGestureStart)
    val latestEnd by rememberUpdatedState(onGestureEnd)
    fun Modifier.drag(handle: VideoAudioLaneHandle) = pointerInput(track.id, handle, enabled) {
        if (!enabled) return@pointerInput
        var initial = latestTrack
        var delta = 0f
        detectDragGestures(onDragStart = {
            initial = latestTrack; delta = 0f; dragging = true; latestSelect(initial.id); latestStart()
        }, onDragEnd = { dragging = false; latestEnd() }, onDragCancel = { dragging = false; latestEnd() }) { change, amount ->
            change.consume(); delta += amount.x / density
            val originalTime = if (handle == VideoAudioLaneHandle.END) initial.atMs + initial.durationMs else initial.atMs
            val target = latestScale.timeAt(latestScale.xAt(originalTime) + delta)
            latestChange(VideoAudioLaneEdit.apply(initial, handle, target, projectDurationMs))
        }
    }
    val start = scale.xAt(track.atMs)
    val end = scale.xAt(track.atMs + track.durationMs)
    val width = (end - start).coerceAtLeast(1f)
    val handles = selected && (width >= 40f || dragging)
    Box(Modifier.fillMaxWidth().height(38.dp).clipToBounds()) {
        Box(Modifier.offset { IntOffset(((start - scrollDp) * density).roundToInt(), 0) }
            .width(width.dp).fillMaxHeight().clip(RoundedCornerShape(7.dp))
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
                Box(Modifier.align(Alignment.CenterStart).width(17.dp).fillMaxHeight().drag(VideoAudioLaneHandle.START),
                    contentAlignment = Alignment.Center) { Box(Modifier.width(2.dp).height(15.dp).background(Color(0xFF54205E))) }
                Box(Modifier.align(Alignment.CenterEnd).width(17.dp).fillMaxHeight().drag(VideoAudioLaneHandle.END),
                    contentAlignment = Alignment.Center) { Box(Modifier.width(2.dp).height(15.dp).background(Color(0xFF54205E))) }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val x = (scale.xAt(positionMs) - scrollDp) * density
            if (x in 0f..size.width) drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
        }
    }
}

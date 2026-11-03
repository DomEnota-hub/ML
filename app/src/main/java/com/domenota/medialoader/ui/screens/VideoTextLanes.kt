package com.domenota.medialoader.ui.screens

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.data.editor.*
import kotlin.math.roundToInt

/** Same time/scroll mapping as the video and audio, with independently editable text intervals. */
@Composable
internal fun VideoTextLanes(project: VideoProjectDraft, texts: List<TextLayer>, selectedId: String?,
    positionMs: Long, scroll: LazyListState, widths: List<Float>, enabled: Boolean,
    onSelect: (String) -> Unit, onChange: (TextLayer) -> Unit,
    onGestureStart: () -> Unit, onGestureEnd: () -> Unit) {
    val scale = remember(project.clips, widths) { VideoAudioLaneScale(project, widths) }
    val density = LocalDensity.current.density
    val scrollDp = scale.prefix(scroll.firstVisibleItemIndex) + scroll.firstVisibleItemScrollOffset / density
    val visible = texts.filter { it.startMs < project.durationMs }
    LazyColumn(Modifier.fillMaxWidth().height((visible.size.coerceAtMost(2) * 40).dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(visible, key = { it.id }) { layer ->
            val from = scale.xAt(layer.startMs)
            val to = scale.xAt(layer.endMs ?: project.durationMs)
            val width = (to - from).coerceAtLeast(40f)
            val current by rememberUpdatedState(layer)
            val changeLayer by rememberUpdatedState(onChange)
            val select by rememberUpdatedState(onSelect)
            val startGesture by rememberUpdatedState(onGestureStart)
            val finishGesture by rememberUpdatedState(onGestureEnd)
            fun Modifier.drag(handle: VideoTextLaneHandle) = pointerInput(layer.id, handle, enabled) {
                if (!enabled) return@pointerInput
                var baseline = current
                var delta = 0f
                detectDragGestures(onDragStart = {
                    baseline = current; delta = 0f; select(baseline.id); startGesture()
                }, onDragEnd = { finishGesture() }, onDragCancel = { finishGesture() }) { pointer, amount ->
                    pointer.consume(); delta += amount.x / density
                    val originalTime = if (handle == VideoTextLaneHandle.END) baseline.endMs ?: project.durationMs else baseline.startMs
                    val target = scale.timeAt(scale.xAt(originalTime) + delta)
                    changeLayer(VideoTextLaneEdit.apply(baseline, handle, target, project.durationMs))
                }
            }
            val selected = layer.id == selectedId
            Box(Modifier.fillMaxWidth().height(36.dp).clipToBounds().testTag("text-lane-${layer.id}")) {
                Box(Modifier.offset { IntOffset(((from - scrollDp) * density).roundToInt(), 0) }
                    .width(width.dp).fillMaxHeight().clip(RoundedCornerShape(7.dp))
                    .background(Color(0xFF2854C9)).border(if (selected) 3.dp else 0.dp,
                        Color(0xFF8AA9FF), RoundedCornerShape(7.dp))) {
                    Text(layer.text.ifBlank { "Текст" } + " | " +
                        String.format(java.util.Locale.ROOT, "%.1fs", ((layer.endMs ?: project.durationMs) - layer.startMs) / 1000f),
                        color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp).align(Alignment.CenterStart))
                    Box(Modifier.fillMaxSize().padding(horizontal = if (selected) 17.dp else 0.dp)
                        .pointerInput(layer.id) { detectTapGestures { if (enabled) select(layer.id) } }
                        .drag(VideoTextLaneHandle.MOVE))
                    if (selected) listOf(Alignment.CenterStart to VideoTextLaneHandle.START,
                        Alignment.CenterEnd to VideoTextLaneHandle.END).forEach { (alignment, handle) ->
                        Box(Modifier.align(alignment).width(17.dp).fillMaxHeight().drag(handle),
                            contentAlignment = Alignment.Center) {
                            Box(Modifier.width(2.dp).height(14.dp).background(Color.White))
                        }
                    }
                }
                Canvas(Modifier.fillMaxSize()) {
                    val x = (scale.xAt(positionMs) - scrollDp) * density
                    if (x in 0f..size.width) drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
                }
            }
        }
    }
}

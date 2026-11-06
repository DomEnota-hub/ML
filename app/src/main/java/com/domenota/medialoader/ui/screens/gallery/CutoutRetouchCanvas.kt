package com.domenota.medialoader.ui.screens.gallery

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Photo-editor-style retouch surface. One finger paints; two fingers zoom/pan.
 * The original ghost and magnifier are strictly transient visual layers:
 * CutoutMaskEditor.preview and snapshot() contain ONLY the current alpha mask.
 */
@Composable
internal fun CutoutRetouchCanvas(
    editor: CutoutMaskEditor,
    restore: Boolean,
    radius: Float,
    panMode: Boolean = false,
    showOriginal: Boolean = false,
    refreshToken: Int = 0,
    resetZoomRevision: Int,
    onZoomChanged: (Float) -> Unit,
    onStrokeDone: () -> Unit,
    magnetic: Boolean = false,
    tracer: MagneticContourTracer? = null,
    attraction: Float = 65f,
    contourArea: Boolean = false,
    draft: List<Offset> = emptyList(),
    draftStops: List<Int> = emptyList(),
    onDraftChanged: (List<Offset>) -> Unit = {},
    onSegmentFinished: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var canvasSize by remember(editor) { mutableStateOf(IntSize.Zero) }
    var revision by remember(editor) { mutableIntStateOf(0) }
    var viewport by remember(editor) { mutableStateOf(CutoutViewport()) }
    var cursor by remember(editor) { mutableStateOf<Offset?>(null) }
    val referenceSize = remember(editor) { IntSize(editor.width, editor.height) }
    val latestDraft by rememberUpdatedState(draft)
    val latestOnDraft by rememberUpdatedState(onDraftChanged)
    val latestOnSegment by rememberUpdatedState(onSegmentFinished)
    val imagePaint = remember { Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG) }
    val ghostPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply { alpha = 76 } }
    val darkPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(30, 30, 35) } }
    val rimPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    } }
    val brushPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(255, 216, 84)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    } }
    val contourPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(255, 211, 79)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    } }
    val contourFill = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(34, 255, 211, 79)
        style = Paint.Style.FILL
    } }
    val contourClosing = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        alpha = 155
        style = Paint.Style.STROKE
        strokeWidth = 1.7f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
    } }

    LaunchedEffect(editor, resetZoomRevision) {
        viewport = CutoutViewport()
        cursor = null
        onZoomChanged(1f)
    }

    Canvas(
        modifier.onSizeChanged { canvasSize = it }
            .pointerInput(editor, restore, radius, panMode, showOriginal, canvasSize,
                magnetic, tracer, attraction) {
                if (canvasSize.width == 0 || canvasSize.height == 0) return@pointerInput
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    var transform = viewport.transform(canvasSize, referenceSize)
                    val start = transform.toImage(first.position)
                    val inBounds = start.x >= 0f && start.y >= 0f &&
                        start.x < editor.width && start.y < editor.height
                    var painting = !magnetic && !panMode && !showOriginal && inBounds
                    var tracing = magnetic && tracer != null && !panMode && !showOriginal && inBounds
                    var multiTouch = false
                    var previous = start
                    val startingDraft = latestDraft
                    var currentDraft = startingDraft
                    if (tracing) {
                        val initial = tracer!!
                        val firstPieces = if (startingDraft.isEmpty())
                            listOf(initial.snap(start, null, attraction))
                        else initial.follow(startingDraft.last(), start, attraction)
                        currentDraft = (startingDraft + firstPieces).take(12_000)
                        latestOnDraft(currentDraft)
                        cursor = first.position
                        revision++
                        first.consume()
                    } else if (painting) {
                        editor.startStroke(start.x, start.y, radius, restore)
                        cursor = first.position
                        revision++
                        first.consume()
                    }
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                if (!multiTouch) {
                                    // Undo the dot/partial stroke before any pinch manipulation.
                                    if (painting) editor.cancelStroke()
                                    if (tracing) latestOnDraft(startingDraft)
                                    painting = false
                                    tracing = false
                                    multiTouch = true
                                    cursor = null
                                    revision++
                                }
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                if (centroid.x.isFinite() && centroid.y.isFinite()) {
                                    viewport = viewport.gesture(canvasSize, referenceSize,
                                        centroid, panChange, zoomChange)
                                    onZoomChanged(viewport.zoom)
                                    revision++
                                }
                                event.changes.forEach { if (it.pressed) it.consume() }
                            } else if (panMode && !multiTouch && pressed.isNotEmpty()) {
                                val active = pressed.firstOrNull { it.id == first.id }
                                if (active != null) {
                                    val delta = active.position - active.previousPosition
                                    viewport = viewport.gesture(canvasSize, referenceSize,
                                        active.position, delta, 1f)
                                    onZoomChanged(viewport.zoom)
                                    active.consume()
                                    revision++
                                }
                            } else if (!multiTouch && pressed.isNotEmpty() && tracing) {
                                val active = pressed.firstOrNull { it.id == first.id }
                                if (active != null) {
                                    transform = viewport.transform(canvasSize, referenceSize)
                                    val mapped = transform.toImage(active.position)
                                    val next = Offset(mapped.x.coerceIn(0f, editor.width - 1f),
                                        mapped.y.coerceIn(0f, editor.height - 1f))
                                    val added = tracer!!.follow(previous, next, attraction)
                                    if (added.isNotEmpty() && currentDraft.size < 12_000) {
                                        currentDraft = (currentDraft + added).take(12_000)
                                        latestOnDraft(currentDraft)
                                    }
                                    previous = next
                                    cursor = active.position
                                    active.consume()
                                    revision++
                                }
                            } else if (!multiTouch && pressed.isNotEmpty() && painting) {
                                val active = pressed.firstOrNull { it.id == first.id }
                                if (active != null) {
                                    transform = viewport.transform(canvasSize, referenceSize)
                                    val mapped = transform.toImage(active.position)
                                    val next = Offset(mapped.x.coerceIn(0f, editor.width - 1f),
                                        mapped.y.coerceIn(0f, editor.height - 1f))
                                    editor.dragStroke(previous.x, previous.y, next.x, next.y,
                                        radius, restore)
                                    previous = next
                                    cursor = active.position
                                    active.consume()
                                    revision++
                                }
                            }
                            if (pressed.isEmpty()) break
                        }
                    } finally {
                        if (painting && !multiTouch) {
                            editor.endStroke()
                            onStrokeDone()
                        } else if (painting) editor.cancelStroke()
                        if (tracing && !multiTouch && currentDraft.size > startingDraft.size)
                            latestOnSegment(startingDraft.size)
                        cursor = null
                        revision++
                    }
                }
            }
    ) {
        @Suppress("UNUSED_VARIABLE")
        val redraw = revision + refreshToken
        val transform = viewport.transform(IntSize(size.width.roundToInt(), size.height.roundToInt()), referenceSize)
        val rect = RectF(transform.left, transform.top,
            transform.left + editor.width * transform.scale,
            transform.top + editor.height * transform.scale)

        drawIntoCanvas { composeCanvas ->
            val native = composeCanvas.nativeCanvas
            val saved = native.save()
            native.clipRect(0f, 0f, size.width, size.height)
            if (restore || showOriginal) {
                // Never part of editor.snapshot() or exported PNG.
                val oldAlpha = ghostPaint.alpha
                ghostPaint.alpha = if (showOriginal) 255 else 76
                native.drawBitmap(editor.sourceReference, null, rect, ghostPaint)
                ghostPaint.alpha = oldAlpha
            }
            if (!showOriginal) native.drawBitmap(editor.preview, null, rect, imagePaint)
            native.restoreToCount(saved)
        }

        // Comparison view is deliberately clean: no draft contour or control points.
        if (!showOriginal && magnetic && draft.isNotEmpty()) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val saved = native.save()
                native.clipRect(0f, 0f, size.width, size.height)
                val trace = Path().apply {
                    val start = transform.toScreen(draft[0])
                    moveTo(start.x, start.y)
                    for (i in 1 until draft.size) {
                        val p = transform.toScreen(draft[i])
                        lineTo(p.x, p.y)
                    }
                }
                if (contourArea && draft.size >= 3) {
                    val closed = Path(trace).apply { close() }
                    native.drawPath(closed, contourFill)
                    val first = transform.toScreen(draft.first())
                    val last = transform.toScreen(draft.last())
                    native.drawLine(last.x, last.y, first.x, first.y, contourClosing)
                }
                native.drawPath(trace, contourPaint)
                // Each finished gesture creates an editable control point.
                for (stop in draftStops) {
                    if (stop > 0 && stop <= draft.size) {
                        val anchor = transform.toScreen(draft[stop - 1])
                        native.drawCircle(anchor.x, anchor.y, 4.dp.toPx(), darkPaint)
                        native.drawCircle(anchor.x, anchor.y, 2.5.dp.toPx(), contourPaint)
                    }
                }
                native.restoreToCount(saved)
            }
        }

        cursor?.let { pointer ->
            val pointOnBitmap = if (magnetic && draft.isNotEmpty())
                draft.last() else transform.toImage(pointer)
            if (pointOnBitmap.x < 0f || pointOnBitmap.x >= editor.width ||
                pointOnBitmap.y < 0f || pointOnBitmap.y >= editor.height) return@let
            if (magnetic) {
                drawCircle(Color(0xFFFFD854), 4.dp.toPx(), transform.toScreen(pointOnBitmap),
                    style = Stroke(width = 1.7.dp.toPx()))
            } else {
                drawCircle(Color(0xFFFFD854), radius * transform.scale, pointer,
                    style = Stroke(width = 1.7.dp.toPx()))
            }

            // Floating 116dp magnifier lives away from the finger, mirrors zoom/pan
            // mapping, and moves to the opposite side near view edges.
            val bubbleRadius = min(58.dp.toPx(), min(size.width, size.height) * .20f)
            val margin = 9.dp.toPx()
            val delta = bubbleRadius * 2.6f
            val centerX = if (pointer.x > size.width * .5f)
                (pointer.x - delta).coerceIn(bubbleRadius + margin, size.width - bubbleRadius - margin)
            else
                (pointer.x + delta).coerceIn(bubbleRadius + margin, size.width - bubbleRadius - margin)
            val centerY = if (pointer.y > size.height * .47f)
                (pointer.y - delta).coerceIn(bubbleRadius + margin, size.height - bubbleRadius - margin)
            else
                (pointer.y + delta).coerceIn(bubbleRadius + margin, size.height - bubbleRadius - margin)
            val srcRadius = max(24f, radius * 2f)
            val left = (pointOnBitmap.x - srcRadius).roundToInt().coerceIn(0, editor.width - 1)
            val top = (pointOnBitmap.y - srcRadius).roundToInt().coerceIn(0, editor.height - 1)
            val right = (pointOnBitmap.x + srcRadius).roundToInt().coerceIn(left + 1, editor.width)
            val bottom = (pointOnBitmap.y + srcRadius).roundToInt().coerceIn(top + 1, editor.height)
            val sourceRect = Rect(left, top, right, bottom)
            val destination = RectF(centerX - bubbleRadius, centerY - bubbleRadius,
                centerX + bubbleRadius, centerY + bubbleRadius)
            drawIntoCanvas { composeCanvas ->
                val native = composeCanvas.nativeCanvas
                val saved = native.save()
                native.drawCircle(centerX, centerY, bubbleRadius + 2.dp.toPx(), darkPaint)
                val circle = Path().apply { addCircle(centerX, centerY, bubbleRadius, Path.Direction.CW) }
                native.clipPath(circle)
                if (restore) native.drawBitmap(editor.sourceReference, sourceRect, destination, ghostPaint)
                native.drawBitmap(editor.preview, sourceRect, destination, imagePaint)
                native.restoreToCount(saved)
                native.drawCircle(centerX, centerY, bubbleRadius, rimPaint)
                val pixelSizeOnLoupe = (bubbleRadius * 2f) / max(1, right - left)
                val actualX = destination.left + (pointOnBitmap.x - left) * pixelSizeOnLoupe
                val actualY = destination.top + (pointOnBitmap.y - top) *
                    (bubbleRadius * 2f) / max(1, bottom - top)
                native.drawCircle(actualX, actualY,
                    if (magnetic) 5.dp.toPx()
                    else (radius * pixelSizeOnLoupe).coerceAtMost(bubbleRadius - 4.dp.toPx()),
                    brushPaint)
                native.drawLine(actualX - 4.dp.toPx(), actualY,
                    actualX + 4.dp.toPx(), actualY, brushPaint)
                native.drawLine(actualX, actualY - 4.dp.toPx(),
                    actualX, actualY + 4.dp.toPx(), brushPaint)
            }
        }
    }
}

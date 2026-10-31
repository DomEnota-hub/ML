package com.domenota.medialoader.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.editor.PhotoGraphicWatermark
import com.domenota.medialoader.data.editor.PhotoGraphicWatermarkRenderer
import com.domenota.medialoader.data.editor.photoWatermarkBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/** Live view gestures are presentation-only: they do not alter exported dimensions. */
internal class PhotoViewportState {
    var scale by mutableFloatStateOf(1f)
    var translation by mutableStateOf(Offset.Zero)
    fun reset() {
        scale = 1f
        translation = Offset.Zero
    }
}

internal fun photoPanLimits(
    viewportWidth: Float, viewportHeight: Float,
    imageWidth: Int, imageHeight: Int, zoom: Float,
): Offset {
    if (viewportWidth <= 0f || viewportHeight <= 0f || imageWidth <= 0 || imageHeight <= 0)
        return Offset.Zero
    val fit = min(viewportWidth / imageWidth, viewportHeight / imageHeight)
    val scaledW = imageWidth * fit * zoom
    val scaledH = imageHeight * fit * zoom
    return Offset(((scaledW - viewportWidth) / 2f).coerceAtLeast(0f),
        ((scaledH - viewportHeight) / 2f).coerceAtLeast(0f))
}

internal fun clampPhotoPan(offset: Offset, limits: Offset) = Offset(
    offset.x.coerceIn(-limits.x, limits.x),
    offset.y.coerceIn(-limits.y, limits.y)
)

@Composable
internal fun PhotoZoomViewport(
    bitmap: Bitmap,
    state: PhotoViewportState,
    graphic: PhotoGraphicWatermark?,
    onMoveGraphic: (Float, Float) -> Unit,
    onGraphicDragEnd: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var markBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var markError by remember { mutableStateOf<String?>(null) }
    val updatedMove by rememberUpdatedState(onMoveGraphic)
    val updatedFinish by rememberUpdatedState(onGraphicDragEnd)

    LaunchedEffect(graphic?.uri) {
        markBitmap = null
        markError = null
        val uri = graphic?.uri ?: return@LaunchedEffect
        try {
            markBitmap = withContext(Dispatchers.IO) {
                PhotoGraphicWatermarkRenderer.decode(context, uri)
            }
        } catch (error: Exception) { markError = error.message }
    }
    DisposableEffect(markBitmap) {
        val owned = markBitmap
        onDispose { if (owned != null && !owned.isRecycled) owned.recycle() }
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (state.scale * zoomChange).coerceIn(1f, 5f)
        val limits = photoPanLimits(viewportSize.width.toFloat(), viewportSize.height.toFloat(),
            bitmap.width, bitmap.height, nextScale)
        state.scale = nextScale
        state.translation = clampPhotoPan(state.translation + panChange, limits)
    }
    val densityScale = with(density) { 1.dp.toPx() }
    Box(Modifier.fillMaxSize().clipToBounds()
        .onSizeChanged { size ->
            viewportSize = size
            state.translation = clampPhotoPan(state.translation,
                photoPanLimits(size.width.toFloat(), size.height.toFloat(),
                    bitmap.width, bitmap.height, state.scale))
        }
        .transformable(transformState)
        .pointerInput(bitmap.width, bitmap.height) {
            detectTapGestures(onDoubleTap = { state.reset() })
        }, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            scaleX = state.scale
            scaleY = state.scale
            translationX = state.translation.x
            translationY = state.translation.y
        }) {
            Image(bitmap.asImageBitmap(), contentDescription = "Просмотр фотографии",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit)
            val currentMark = markBitmap
            if (graphic != null && currentMark != null &&
                viewportSize.width > 0 && viewportSize.height > 0) {
                val fit = photoCanvasRect(viewportSize.width.toFloat(), viewportSize.height.toFloat(),
                    bitmap.width, bitmap.height)
                val bounds = photoWatermarkBounds(bitmap.width, bitmap.height,
                    currentMark.width, currentMark.height, graphic)
                val leftPx = fit.x + bounds.left / bitmap.width * fit.width
                val topPx = fit.y + bounds.top / bitmap.height * fit.height
                val widthPx = bounds.width() / bitmap.width * fit.width
                val heightPx = bounds.height() / bitmap.height * fit.height
                Image(currentMark.asImageBitmap(), contentDescription = "Перетащить водяной знак",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.offset { IntOffset(leftPx.roundToInt(), topPx.roundToInt()) }
                        .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
                        .pointerInput(graphic.uri, bitmap.width, bitmap.height, viewportSize) {
                            detectDragGestures(
                                onDrag = { change, delta ->
                                    change.consume()
                                    updatedMove(delta.x / fit.width, delta.y / fit.height)
                                },
                                onDragEnd = { updatedFinish() },
                                onDragCancel = { updatedFinish() },
                            )
                        })
            }
        }
    }
}

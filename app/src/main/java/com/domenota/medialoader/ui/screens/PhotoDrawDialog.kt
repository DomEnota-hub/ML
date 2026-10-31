package com.domenota.medialoader.ui.screens

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.editor.PhotoDrawing
import com.domenota.medialoader.data.editor.PhotoMark
import com.domenota.medialoader.data.editor.PhotoMarkKind
import com.domenota.medialoader.data.editor.PhotoPoint
import com.domenota.medialoader.ui.components.EditorWindow
import kotlin.math.min

/** Geometric mapping is shared by drawing and touch input; letterboxes never receive marks. */
internal data class PhotoCanvasRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    fun inside(px: Float, py: Float) = px >= x && py >= y && px <= x + width && py <= y + height
    fun normalize(px: Float, py: Float): PhotoPoint =
        PhotoDrawing.clamp((px - x) / width, (py - y) / height)
}
internal fun photoCanvasRect(viewWidth: Float, viewHeight: Float, imageWidth: Int, imageHeight: Int): PhotoCanvasRect {
    require(viewWidth > 0f && viewHeight > 0f && imageWidth > 0 && imageHeight > 0)
    val scale = min(viewWidth / imageWidth, viewHeight / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    return PhotoCanvasRect((viewWidth - width) / 2f, (viewHeight - height) / 2f, width, height)
}

@Composable
internal fun PhotoDrawDialog(
    preview: Bitmap,
    onApply: (List<PhotoMark>) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(PhotoMarkKind.PEN) }
    var color by remember { mutableIntStateOf(AndroidColor.WHITE) }
    var thickness by remember { mutableFloatStateOf(.008f) }
    var committed by remember { mutableStateOf<List<PhotoMark>>(emptyList()) }
    var active by remember { mutableStateOf<List<PhotoPoint>>(emptyList()) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val swatches = listOf(AndroidColor.WHITE, AndroidColor.BLACK,
        AndroidColor.RED, 0xFFFF9800.toInt(), AndroidColor.YELLOW,
        AndroidColor.GREEN, AndroidColor.CYAN, 0xFF8745EF.toInt())
    EditorWindow(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Отмена", tint = Color.White) }
                Text("Рисование и фигуры", modifier = Modifier.weight(1f), color = Color.White,
                    style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { if (committed.isNotEmpty()) committed = committed.dropLast(1) },
                    enabled = committed.isNotEmpty()) { Icon(Icons.Rounded.Undo, "Убрать последний штрих") }
                TextButton(onClick = { onApply(committed) }, enabled = committed.isNotEmpty()) {
                    Text("Готово")
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black),
                contentAlignment = Alignment.Center) {
                Image(preview.asImageBitmap(), contentDescription = "Изображение для рисования",
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                Canvas(Modifier.fillMaxSize().onSizeChanged { viewport = it }
                    .pointerInput(preview, mode, color, thickness, viewport) {
                        if (viewport.width <= 0 || viewport.height <= 0) return@pointerInput
                        val rect = photoCanvasRect(
                            viewport.width.toFloat(), viewport.height.toFloat(), preview.width, preview.height)
                        detectDragGestures(
                            onDragStart = { at ->
                                active = if (rect.inside(at.x, at.y))
                                    listOf(rect.normalize(at.x, at.y)) else emptyList()
                            },
                            onDrag = { change, _ ->
                                if (active.isNotEmpty()) {
                                    change.consume()
                                    val next = rect.normalize(change.position.x, change.position.y)
                                    active = if (mode == PhotoMarkKind.PEN)
                                        (active + next).take(PhotoDrawing.MAX_MARKS * 6)
                                    else listOf(active.first(), next)
                                }
                            },
                            onDragEnd = {
                                if (active.isNotEmpty() && committed.size < PhotoDrawing.MAX_MARKS) {
                                    committed = committed + PhotoMark(mode, active.take(900), color, thickness)
                                }
                                active = emptyList()
                            },
                            onDragCancel = { active = emptyList() },
                        )
                    }) {
                    if (viewport.width > 0 && viewport.height > 0) {
                        val rect = photoCanvasRect(size.width, size.height, preview.width, preview.height)
                        val canvas = drawContext.canvas.nativeCanvas
                        val save = canvas.save()
                        canvas.translate(rect.x, rect.y)
                        canvas.scale(rect.width / preview.width, rect.height / preview.height)
                        val marks = if (active.isNotEmpty())
                            committed + PhotoMark(mode, active.take(900), color, thickness)
                            else committed
                        PhotoDrawing.draw(canvas, preview.width, preview.height, marks)
                        canvas.restoreToCount(save)
                    }
                }
            }
            Text("Рисуй по фотографии; готовые штрихи можно отменить",
                color = Color.LightGray, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                PhotoMarkKind.entries.forEach { kind ->
                    FilterChip(selected = mode == kind, onClick = { mode = kind },
                        label = { Text(kind.title) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Толщина", color = Color.White, style = MaterialTheme.typography.labelMedium)
                Slider(value = thickness, onValueChange = { thickness = it },
                    valueRange = .002f..0.05f, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                swatches.forEach { candidate ->
                    Surface(onClick = { color = candidate }, shape = CircleShape,
                        modifier = Modifier.size(if (color == candidate) 36.dp else 30.dp),
                        color = Color(candidate),
                        border = if (color == candidate) androidx.compose.foundation.BorderStroke(2.dp,
                            Color(0xFF7B4DFF)) else null) {}
                }
            }
        }
    }
}

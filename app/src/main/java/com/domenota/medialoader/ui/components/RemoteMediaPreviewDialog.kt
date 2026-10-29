package com.domenota.medialoader.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.provider.PreviewPlayback
import com.domenota.medialoader.data.editor.PreviewZoom
import com.domenota.medialoader.data.preview.RemoteImageLoader
import com.domenota.medialoader.ui.model.MediaKind
import com.domenota.medialoader.ui.model.PreviewMediaUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun RemoteMediaPreviewDialog(initialId: String, items: List<PreviewMediaUi>,
    resolvePlayback: suspend (String) -> PreviewPlayback, onToggle: (String) -> Unit, onDismiss: () -> Unit) {
    val initial = items.firstOrNull { it.id == initialId } ?: return
    val photos = items.filter { it.kind == MediaKind.IMAGE }
    val pager = rememberPagerState(initialPage = photos.indexOfFirst { it.id == initialId }.coerceAtLeast(0),
        pageCount = { photos.size })
    val scope = rememberCoroutineScope()
    val current = if (initial.kind == MediaKind.IMAGE) photos.getOrNull(pager.currentPage) ?: initial else initial
    var zoomed by remember { mutableStateOf(false) }
    EditorWindow(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Закрыть предпросмотр") }
                    Column(Modifier.weight(1f)) {
                        Text("Предпросмотр", style = MaterialTheme.typography.titleMedium)
                        Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (items.none { it.kind == MediaKind.IMAGE }) {
                        RadioButton(selected = current.selected, onClick = { onToggle(current.id) },
                            modifier = Modifier.semantics { contentDescription = "Выбрать: ${current.title}" })
                    } else MediaSelectionMark(current.selected, { onToggle(current.id) }, "Выбрать: ${current.title}")
                }
                if (initial.kind == MediaKind.IMAGE) {
                    HorizontalPager(pager, modifier = Modifier.weight(1f).fillMaxWidth(),
                        userScrollEnabled = !zoomed, key = { photos[it].id }) { index ->
                        RemotePhotoPage(photos[index], active = pager.currentPage == index, onZoomed = { zoomed = it })
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        OutlinedIconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                            enabled = pager.currentPage > 0) { Icon(Icons.AutoMirrored.Rounded.NavigateBefore, "Предыдущее фото") }
                        Text("${pager.currentPage + 1} из ${photos.size}")
                        OutlinedIconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                            enabled = pager.currentPage < photos.lastIndex) { Icon(Icons.AutoMirrored.Rounded.NavigateNext, "Следующее фото") }
                    }
                } else StreamingMediaPreview(current, resolvePlayback, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun RemotePhotoPage(item: PreviewMediaUi, active: Boolean, onZoomed: (Boolean) -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(item.id) { mutableStateOf(true) }
    var attempt by remember(item.id) { mutableIntStateOf(0) }
    var zoom by remember(item.id) { mutableStateOf(PreviewZoom()) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(active, zoom.scale) { if (active) onZoomed(zoom.scale > 1f) else zoom = PreviewZoom() }
    LaunchedEffect(item.id, item.fullImageUrl, attempt) {
        loading = true
        try { bitmap = withContext(Dispatchers.IO) {
            (item.fullImageUrl ?: item.previewUrl)?.let { url ->
                RemoteImageLoader.fetch(context, url, item.providerId, maxDimension = 2048)
            }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { bitmap = null }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black).clipToBounds()
            .onSizeChanged { if (size != it) { size = it; zoom = PreviewZoom() } }
            .previewZoomGestures({ zoom }, { bitmap?.let { it.width to it.height } }, { if (bitmap != null) zoom = it })
            .pointerInput(item.id) { detectTapGestures(onDoubleTap = {
                if (bitmap != null) zoom = if (zoom.scale > 1f) PreviewZoom() else zoom.transform(2f, 0f, 0f, it.x, it.y,
                    size.width.toFloat(), size.height.toFloat(), bitmap?.width ?: 0, bitmap?.height ?: 0)
            }) }, contentAlignment = Alignment.Center) {
            bitmap?.let { image -> Image(image.asImageBitmap(), item.title,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom.scale; scaleY = zoom.scale; translationX = zoom.x; translationY = zoom.y
                }, contentScale = ContentScale.Fit) }
            if (loading) CircularProgressIndicator()
            else if (bitmap == null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Не удалось открыть изображение", color = Color.White)
                OutlinedButton(onClick = { attempt++ }) { Text("Повторить") }
            }
        }
        OutlinedButton(onClick = { zoom = PreviewZoom() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("${(zoom.scale * 100).toInt()}% · сбросить масштаб")
        }
    }
}

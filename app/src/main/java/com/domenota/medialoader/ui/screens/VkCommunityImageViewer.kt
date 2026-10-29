package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.domenota.medialoader.data.preview.RemoteImageLoader
import com.domenota.medialoader.data.vkcommunity.VkAdStatus
import com.domenota.medialoader.data.vkcommunity.VkCommunityDatabase
import com.domenota.medialoader.data.vkcommunity.VkItemPhase
import com.domenota.medialoader.data.vkcommunity.VkStoredImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loads only visible and neighboring images, so a large result set remains cheap to browse. */
@Composable
internal fun VkCommunityImageViewer(
    jobId: String, initialIndex: Int, total: Int, readOnly: Boolean,
    database: VkCommunityDatabase, onSelect: (VkStoredImage, Boolean) -> Unit,
    onSelectPost: (VkStoredImage, Boolean) -> Unit, onClose: (Int) -> Unit,
) {
    val state = rememberPagerState(initialPage = initialIndex, pageCount = { total.coerceAtLeast(1) })
    val loaded = remember(jobId) { mutableStateMapOf<Int, VkStoredImage>() }
    val current = loaded[state.currentPage]
    Dialog(onDismissRequest = { onClose(state.currentPage) },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        com.domenota.medialoader.ui.theme.TransparentWindowNavigation()
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (current != null) RoundSelection(current.selected, isSelectable(current, readOnly)) {
                    loaded[state.currentPage] = current.copy(selected = it)
                    onSelect(current, it)
                }
                else Box(Modifier.size(44.dp))
                Text("${state.currentPage + 1} из $total", Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { onClose(state.currentPage) }) {
                    Icon(Icons.Rounded.Close, "Закрыть")
                }
            }
            HorizontalPager(state = state, modifier = Modifier.weight(1f)) { index ->
                VkViewerPage(jobId, index, database, onLoaded = { loaded[index] = it })
            }
            current?.let { item ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(formatPostDate(item.candidate.postEpochSecond), Modifier.weight(1f))
                    if (item.candidate.adStatus != VkAdStatus.NONE)
                        AdBadge(item.candidate.adStatus)
                    if (!readOnly) TextButton(onClick = {
                        loaded.keys.toList().forEach { index ->
                            loaded[index]?.takeIf { it.candidate.postKey == item.candidate.postKey &&
                                it.phase in setOf(VkItemPhase.FOUND, VkItemPhase.ERROR) }
                                ?.let { loaded[index] = it.copy(selected = false) }
                        }
                        onSelectPost(item, false)
                    }) { Text("Снять публикацию") }
                }
            }
        }
    }
}

@Composable
private fun VkViewerPage(jobId: String, index: Int, database: VkCommunityDatabase,
    onLoaded: (VkStoredImage) -> Unit) {
    val context = LocalContext.current
    var image by remember(jobId, index) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loading by remember(jobId, index) { mutableStateOf(true) }
    var found by remember(jobId, index) { mutableStateOf(false) }
    LaunchedEffect(jobId, index) {
        loading = true
        val item = withContext(Dispatchers.IO) { database.images(jobId, index, 1).firstOrNull() }
        found = item != null
        if (item != null) {
            onLoaded(item)
            image = withContext(Dispatchers.IO) {
                RemoteImageLoader.fetch(context, item.candidate.url, "vk", maxDimension = 2048)
            }
        }
        loading = false
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            image != null -> Image(image!!.asImageBitmap(), "Изображение VK",
                Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            loading -> CircularProgressIndicator()
            found -> Text("Изображение недоступно. Ссылка могла устареть.")
            else -> Text("Результат недоступен")
        }
    }
}

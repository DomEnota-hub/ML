package com.domenota.medialoader.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.MediaType

/** One active video player; photo zoom takes precedence over changing pages. */
@Composable
internal fun MediaSwipePages(start: DownloadEntity, queue: List<DownloadEntity>,
    onCurrent: (DownloadEntity) -> Unit, modifier: Modifier = Modifier,
    onOpenExternal: (DownloadEntity) -> Unit = {}, allowExternal: Boolean = false,
    onFullscreenChange: (Boolean) -> Unit = {}, onLockedChange: (Boolean) -> Unit = {},
    onVideoOrientation: (Boolean) -> Unit = {}) {
    val rows = remember(start.id, queue) {
        val valid = queue.filter { it.mediaType != MediaType.AUDIO && it.savedUri != null }.distinctBy { it.id }
        if (valid.any { it.id == start.id }) valid else listOf(start) + valid
    }
    val pager = rememberPagerState(initialPage = rows.indexOfFirst { it.id == start.id }.coerceAtLeast(0), pageCount = { rows.size })
    var zoomed by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    LaunchedEffect(pager.settledPage, rows) {
        rows.getOrNull(pager.settledPage)?.let(onCurrent)
        zoomed = false; locked = false; fullscreen = false
        onFullscreenChange(false); onLockedChange(false)
    }
    androidx.activity.compose.BackHandler(fullscreen) { fullscreen = false; onFullscreenChange(false) }
    HorizontalPager(pager, modifier = modifier.testTag("media-pages"), key = { rows[it].id }, userScrollEnabled = !zoomed && !locked && !fullscreen) { index ->
        val row = rows[index]
        val uri = row.savedUri?.let(Uri::parse)
        if (uri == null) Text("Файл недоступен")
        else if (row.mediaType == MediaType.PHOTO) {
            ZoomablePhoto(row, uri, index + 1, rows.size,
                { if (pager.currentPage == index) zoomed = it }, Modifier.fillMaxSize(), showMetadata = false)
        } else if (pager.currentPage == index && !pager.isScrollInProgress) {
            LocalVideoPlayer(row.id, uri, fullscreen, locked,
                { locked = it; onLockedChange(it) }, { fullscreen = it; onFullscreenChange(it) }, onVideoOrientation,
                { onOpenExternal(row) }, allowExternal = allowExternal && !row.isPrivate,
                modifier = Modifier.fillMaxSize(), galleryPagingEnabled = true)
        }
    }
}

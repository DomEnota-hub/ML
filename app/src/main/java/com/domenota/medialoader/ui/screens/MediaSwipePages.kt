package com.domenota.medialoader.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.MediaType

/** One active video player; photo zoom takes precedence over changing pages. */
@Composable
internal fun MediaSwipePages(start: DownloadEntity, queue: List<DownloadEntity>,
    onCurrent: (DownloadEntity) -> Unit, modifier: Modifier = Modifier,
    onOpenExternal: (DownloadEntity) -> Unit = {}, allowExternal: Boolean = false,
    onFullscreenChange: (Boolean) -> Unit = {}, onLockedChange: (Boolean) -> Unit = {},
    onVideoOrientation: (Boolean) -> Unit = {}) {
    val photoCache = remember { PhotoPreviewCache() }
    DisposableEffect(photoCache) { onDispose { photoCache.clear() } }
    val rows = remember(start.id, queue) {
        val valid = queue.filter { it.mediaType != MediaType.AUDIO && it.savedUri != null }.distinctBy { it.id }
        if (valid.any { it.id == start.id }) valid else listOf(start) + valid
    }
    val pager = rememberPagerState(initialPage = rows.indexOfFirst { it.id == start.id }.coerceAtLeast(0), pageCount = { rows.size })
    val scope = rememberCoroutineScope()
    val videoTypes = remember(rows) { rows.map { it.mediaType } }
    val showEpisodeArrows = remember(videoTypes) { videoTypes.count { it == MediaType.VIDEO } > 1 }
    var episodeNavigation by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    fun navigateVideo(targetIndex: Int, keepPlaying: Boolean) {
        val target = rows.getOrNull(targetIndex) ?: return
        episodeNavigation = target.id to keepPlaying
        scope.launch { pager.animateScrollToPage(targetIndex) }
    }
    var zoomed by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    LaunchedEffect(pager.settledPage, rows) {
        val current = rows.getOrNull(pager.settledPage)
        val fromSkipButton = current != null && episodeNavigation?.first == current.id
        current?.let(onCurrent)
        if (!fromSkipButton) {
            episodeNavigation = null
            zoomed = false; locked = false; fullscreen = false
            onFullscreenChange(false); onLockedChange(false)
        }
    }
    androidx.activity.compose.BackHandler(fullscreen) { fullscreen = false; onFullscreenChange(false) }
    HorizontalPager(pager, beyondViewportPageCount = 1, modifier = modifier.testTag("media-pages"), key = { rows[it].id }, userScrollEnabled = !zoomed && !locked && !fullscreen) { index ->
        val row = rows[index]
        val uri = row.savedUri?.let(Uri::parse)
        if (uri == null) Text("Файл недоступен")
        else if (row.mediaType == MediaType.PHOTO) {
            ZoomablePhoto(row, uri, index + 1, rows.size,
                { if (pager.currentPage == index) zoomed = it }, Modifier.fillMaxSize(), showMetadata = false, photoCache = photoCache, active = pager.settledPage == index)
        } else if (pager.currentPage == index && !pager.isScrollInProgress) {
            val previousVideo = VideoPlayerUiPolicy.adjacentVideoIndex(videoTypes, index, -1)
            val nextVideo = VideoPlayerUiPolicy.adjacentVideoIndex(videoTypes, index, 1)
            val fromSkipButton = episodeNavigation?.first == row.id
            LocalVideoPlayer(row.id, uri, fullscreen, locked,
                { locked = it; onLockedChange(it) }, { fullscreen = it; onFullscreenChange(it) }, onVideoOrientation,
                { onOpenExternal(row) }, allowExternal = allowExternal && !row.isPrivate,
                modifier = Modifier.fillMaxSize(), galleryPagingEnabled = true,
                showAlbumSkipButton = showEpisodeArrows,
                onPreviousVideo = previousVideo?.let { target ->
                    { playing: Boolean -> navigateVideo(target, playing) }
                },
                onNextVideo = nextVideo?.let { target ->
                    { playing: Boolean -> navigateVideo(target, playing) }
                },
                startAtBeginning = fromSkipButton,
                autoplayOnNavigation = fromSkipButton && episodeNavigation?.second == true)
        }
    }
}

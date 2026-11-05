package com.domenota.medialoader.ui.screens

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.domenota.medialoader.data.playback.VideoWatchProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The pager poster and initial seek must describe the same saved playback position. */
internal data class VideoViewerPosterState(val positionMs: Long, val bitmap: Bitmap?)

private val viewerPosters = object : LruCache<VideoTrimBoundaryPreview.Request, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: VideoTrimBoundaryPreview.Request, value: Bitmap) = value.allocationByteCount
}
private val viewerPosterDecode = Mutex()

@Composable
internal fun rememberVideoViewerPoster(itemId: String, uri: Uri): VideoViewerPosterState {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("video_playback_positions", Context.MODE_PRIVATE)
    }
    var positionMs by remember(prefs, itemId, uri) {
        mutableLongStateOf(VideoWatchProgress.resumePosition(prefs, itemId))
    }
    // A neighboring page can stay composed while its player saves a newer resume position.
    DisposableEffect(prefs, itemId, uri) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            positionMs = VideoWatchProgress.resumePosition(prefs, itemId)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val request = remember(uri, positionMs) { VideoTrimBoundaryPreview.Request(uri.toString(), positionMs) }
    var bitmap by remember(request) { mutableStateOf(viewerPosters.get(request)) }
    LaunchedEffect(context, request) {
        if (bitmap == null) bitmap = withContext(Dispatchers.IO) {
            viewerPosterDecode.withLock {
                viewerPosters.get(request) ?: VideoTrimBoundaryPreview.decode(context, request)?.also {
                    viewerPosters.put(request, it)
                }
            }
        }
    }
    return VideoViewerPosterState(positionMs, bitmap)
}

@Composable
internal fun VideoViewerPoster(poster: VideoViewerPosterState, modifier: Modifier = Modifier) {
    Box(modifier.background(Color.Black)) {
        poster.bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit)
        }
    }
}

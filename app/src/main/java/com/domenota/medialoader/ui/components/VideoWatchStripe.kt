package com.domenota.medialoader.ui.components

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import com.domenota.medialoader.data.playback.VideoWatchProgress
import com.domenota.medialoader.ui.model.DownloadUiItem
import com.domenota.medialoader.ui.model.DownloadUiState
import com.domenota.medialoader.ui.model.MediaKind
import com.domenota.medialoader.ui.theme.AccentPurple
import com.domenota.medialoader.ui.theme.isDarkPalette
import androidx.compose.material3.MaterialTheme

@Composable
internal fun VideoWatchStripe(item: DownloadUiItem, modifier: Modifier = Modifier) {
    if (item.kind != MediaKind.VIDEO || item.state != DownloadUiState.COMPLETED) return
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("video_playback_positions", Context.MODE_PRIVATE) }
    var fraction by remember(prefs, item.id, item.durationMs) { mutableFloatStateOf(VideoWatchProgress.read(prefs, item.id, item.durationMs)) }
    DisposableEffect(prefs, item.id, item.durationMs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == item.id || key == "watched_${item.id}" || key == "duration_${item.id}" || key == null)
                fraction = VideoWatchProgress.read(prefs, item.id, item.durationMs)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        fraction = VideoWatchProgress.read(prefs, item.id, item.durationMs)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val accent = if (isDarkPalette) MaterialTheme.colorScheme.primary else AccentPurple
    if (fraction > 0f) Canvas(modifier) {
        drawRect(Color.Black.copy(alpha = 0.55f))
        drawRect(accent, size = Size(size.width * fraction, size.height))
    }
}

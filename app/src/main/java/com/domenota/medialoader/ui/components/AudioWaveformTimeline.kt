package com.domenota.medialoader.ui.components

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Non-interactive waveform: the parent MediaTrimControls exclusively handles seeking and trim.
 * A file-backed waveform is painted immediately after process restart, with no editor-specific
 * scan requirement. Decode remains off the Compose main thread on cache misses.
 */
@Composable
internal fun AudioWaveformTimeline(uri: Uri, durationMs: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var peaks by remember(uri, durationMs) {
        mutableStateOf(peekAudioWaveform(context, uri))
    }
    LaunchedEffect(uri, durationMs) {
        if (durationMs > 0L && peaks == null) {
            peaks = withContext(Dispatchers.IO) {
                runCatching { loadAudioWaveform(context, uri, durationMs) }.getOrNull()
            }
        }
    }
    val color = MaterialTheme.colorScheme.primary
    val calm = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val values = peaks
        if (values.isNullOrEmpty()) {
            drawLine(calm.copy(alpha = .35f), Offset(0f, size.height / 2f),
                Offset(size.width, size.height / 2f), strokeWidth = 1f)
            return@Canvas
        }
        val width = size.width / values.size
        values.forEachIndexed { index, value ->
            val x = (index + .5f) * width
            val barHeight = size.height * (.1f + .85f * value.coerceIn(0f, 1f))
            drawLine(color.copy(alpha = .85f),
                Offset(x, (size.height - barHeight) / 2f),
                Offset(x, (size.height + barHeight) / 2f),
                strokeWidth = (width * .7f).coerceAtLeast(1f), cap = StrokeCap.Round)
        }
    }
}

package com.domenota.medialoader.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import com.domenota.medialoader.ui.components.AppSlider as Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun MusicPreviewTimeline(preview: MusicPreviewPlayer) {
    if (preview.durationMs <= 0L) return
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = preview.positionMs.coerceIn(0L, preview.durationMs).toFloat(),
            onValueChange = { preview.seekTo(it.toLong()) },
            valueRange = 0f..preview.durationMs.toFloat(),
            modifier = Modifier.fillMaxWidth().height(32.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(previewTime(preview.positionMs), style = MaterialTheme.typography.labelSmall)
            Text(previewTime(preview.durationMs), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun previewTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1000L
    return "%d:%02d".format(seconds / 60L, seconds % 60L)
}

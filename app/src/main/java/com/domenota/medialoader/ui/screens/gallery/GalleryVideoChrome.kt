package com.domenota.medialoader.ui.screens.gallery

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Gallery-only Compose controller for Media3 playback.
 * Media3's own PlayerView controller stays disabled in the gallery: no
 * duplicate toolbar, no measured native button coordinates, no media resizes.
 * The GalleryMediaViewer owns the common top/action bars.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryVideoChrome(
    visible: Boolean,
    locked: Boolean,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onLockChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    Box(modifier.fillMaxSize()) {
        if (locked) {
            // The lock remains reachable at the right-center even in clean mode.
            IconButton(
                onClick = { onLockChange(false) },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(48.dp),
            ) {
                Icon(Icons.Rounded.Lock, contentDescription = "Разблокировать управление",
                    modifier = Modifier.size(27.dp), tint = Color.White)
            }
        } else if (visible) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(if (landscape) 20.dp else 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.SkipPrevious,
                        contentDescription = if (hasPrevious) "К началу или предыдущему видео" else "К началу видео",
                        tint = Color.White, modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = onPlayPause, modifier = Modifier.size(64.dp)) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playing) "Пауза" else "Воспроизвести",
                        tint = Color.White, modifier = Modifier.size(43.dp))
                }
                IconButton(onClick = onNext, enabled = hasNext, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Следующее видео",
                        tint = Color.White.copy(alpha = if (hasNext) 1f else .35f),
                        modifier = Modifier.size(30.dp))
                }
            }

            // Time and seek bar are ONE row, above Gallery's actions in
            // portrait and above gesture/navigation areas in landscape.
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = if (landscape) 24.dp else 12.dp)
                    .padding(bottom = if (landscape) 8.dp else 86.dp)
                    .height(48.dp)
                    .background(Color.Black.copy(alpha = .40f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val length = durationMs.coerceAtLeast(1L)
                Text(galleryVideoClock(scrubbing?.toLong() ?: positionMs), color = Color.White,
                    fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Slider(
                    value = (scrubbing ?: positionMs.toFloat()).coerceIn(0f, length.toFloat()),
                    onValueChange = { scrubbing = it },
                    onValueChangeFinished = {
                        scrubbing?.let { onSeekTo(it.toLong()) }
                        scrubbing = null
                    },
                    valueRange = 0f..length.toFloat(),
                    modifier = Modifier.weight(1f).height(40.dp)
                        .semantics { contentDescription = "Положение воспроизведения" },
                    // Never use Material3's default vertical-line thumb in the viewer.
                    // The only allowed handle is this compact circular drawable.
                    thumb = { Box(Modifier.size(12.dp).background(Color.White, CircleShape)) },
                    colors = SliderDefaults.colors(
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.LightGray.copy(alpha = .50f),
                    ),
                )
                Text(galleryVideoClock(durationMs), color = Color.White, fontSize = 12.sp,
                    fontWeight = FontWeight.Medium)
            }
            IconButton(
                onClick = { onLockChange(true) },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(48.dp),
            ) {
                Icon(Icons.Rounded.LockOpen, contentDescription = "Заблокировать управление",
                    tint = Color.White, modifier = Modifier.size(27.dp))
            }
        }
    }
}

internal fun galleryVideoClock(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(
        seconds / 3600, (seconds / 60) % 60, seconds % 60
    ) else "%02d:%02d".format(seconds / 60, seconds % 60)
}

package com.domenota.medialoader.ui.screens.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.domenota.medialoader.ui.components.editorSliderYellow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.ui.components.DownloadThumbnail
import com.domenota.medialoader.ui.model.toUi
import com.domenota.medialoader.ui.screens.audioArtist
import com.domenota.medialoader.ui.screens.audioTitle

@Composable
internal fun MusicLibraryTrackRow(track: DownloadEntity, onPlay: () -> Unit, isCurrent: Boolean = false,
                                  isPlaying: Boolean = false, trailing: @Composable () -> Unit) {
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < .5f
    Row(Modifier.fillMaxWidth().background(if (isCurrent) {
        if (darkTheme) editorSliderYellow.copy(alpha = .11f) else Color(0xFFFFF2CE)
    } else Color.Transparent)
        .clickable(onClick = onPlay).testTag("music-track-${track.id}").heightIn(min = 76.dp).padding(horizontal = 17.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (isCurrent) Box(Modifier.width(3.dp).height(49.dp)
            .background(editorSliderYellow, RoundedCornerShape(3.dp)))
        Box(Modifier.size(53.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
            DownloadThumbnail(track.savedUri, track.recognitionArtworkUrl ?: track.sourceAudioArtworkUrl ?: track.previewUrl,
                track.toUi().kind, Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f)) {
            Text(audioTitle(track), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (isCurrent && darkTheme) editorSliderYellow else MaterialTheme.colorScheme.onSurface)
            val artist = audioArtist(track)
            if (artist != null || isCurrent) Text((if (isCurrent) if (isPlaying) "♪ Воспроизводится · " else "Пауза · " else "") + artist.orEmpty(),
                Modifier.padding(top = 5.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        track.durationMs?.takeIf { it > 0 }?.let { duration ->
            val seconds = duration / 1000
            Text(if (seconds >= 3600) "%d:%02d:%02d".format(seconds/3600,seconds/60%60,seconds%60)
                else "%d:%02d".format(seconds/60,seconds%60), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

package com.domenota.medialoader.ui.screens.music

import com.domenota.medialoader.ui.model.displayAudioTitle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
                                  isPlaying: Boolean = false, rank: Int? = null, listeningCount: Long? = null,
                                  compact: Boolean = false, secondaryTime: String? = null, showDuration: Boolean = true,
                                  selectionMode: Boolean = false, selected: Boolean = false,
                                  onArtworkLongPress: (() -> Unit)? = null,
                                  onToggleSelection: (() -> Unit)? = null,
                                  trailing: @Composable () -> Unit) {
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < .5f
    Row(Modifier.fillMaxWidth().background(if (isCurrent) {
        if (darkTheme) editorSliderYellow.copy(alpha = .11f) else Color(0xFFFFF2CE)
    } else Color.Transparent)
        .clickable(onClick = if(selectionMode) onToggleSelection ?: onPlay else onPlay).semantics { if(isCurrent) stateDescription=if(isPlaying) "Воспроизводится" else "Пауза" }.testTag("music-track-${track.id}").heightIn(min = if(compact)56.dp else 76.dp).padding(horizontal = if(compact)12.dp else 17.dp, vertical = if(compact)4.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if(compact)8.dp else 12.dp)) {
        if (isCurrent) Box(Modifier.width(3.dp).height(49.dp)
            .background(editorSliderYellow, RoundedCornerShape(3.dp)))
        rank?.let { Text(it.toString(),Modifier.widthIn(min=20.dp),color=MaterialTheme.colorScheme.primary,fontWeight=androidx.compose.ui.text.font.FontWeight.Medium) }
        Box(Modifier.size(if(compact)38.dp else 53.dp).clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(
                onClick = if(selectionMode) onToggleSelection ?: onPlay else onPlay,
                onLongClick = onArtworkLongPress,
                onLongClickLabel = "Выбрать трек",
            ).testTag("music-artwork-${track.id}")) {
            DownloadThumbnail(track.savedUri, track.recognitionArtworkUrl ?: track.sourceAudioArtworkUrl ?: track.previewUrl,
                track.toUi().kind, Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f)) {
            Text(displayAudioTitle(track), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (isCurrent && darkTheme) editorSliderYellow else MaterialTheme.colorScheme.onSurface)
            val artist = audioArtist(track)
            if (artist != null) Text(artist,
                Modifier.padding(top = 5.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            listeningCount?.let { Text("Прослушиваний: $it",Modifier.padding(top=4.dp),fontSize=11.sp,color=MaterialTheme.colorScheme.primary) }
        }
        if(secondaryTime!=null)Text(secondaryTime,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        else if(showDuration)track.durationMs?.takeIf { it > 0 }?.let { duration ->
            val seconds = duration / 1000
            Text(if (seconds >= 3600) "%d:%02d:%02d".format(seconds/3600,seconds/60%60,seconds%60)
                else "%d:%02d".format(seconds/60,seconds%60), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(selectionMode && onToggleSelection!=null) {
            Box(Modifier.size(48.dp).testTag("music-selection-${track.id}")
                .combinedClickable(onClick=onToggleSelection,role=Role.Checkbox,onLongClick=null),
                contentAlignment=Alignment.Center) {
                Box(Modifier.size(24.dp).border(2.dp,if(selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,CircleShape)
                    .background(if(selected) MaterialTheme.colorScheme.primary else Color.Transparent,CircleShape),
                    contentAlignment=Alignment.Center) {
                    if(selected) Icon(Icons.Rounded.Check,"Выбрано",Modifier.size(17.dp),
                        tint=MaterialTheme.colorScheme.onPrimary)
                }
            }
        } else trailing()
    }
}

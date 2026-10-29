package com.domenota.medialoader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.data.search.FullSongSource

/** One search at a time. A failed primary search hands the current query to the reserve. */
@Composable
fun FullSongSearchDialog(
    source: FullSongSource,
    initialArtist: String,
    initialTitle: String,
    recognizedArtworkUrl: String?,
    recognitionMessage: String?,
    onDismiss: () -> Unit,
) {
    var query by remember(source) { mutableStateOf(initialArtist to initialTitle) }
    var reserve by remember(source) { mutableStateOf(source == FullSongSource.MUZMO) }
    var automaticFallback by remember(source) { mutableStateOf(false) }
    if (reserve) {
        MuzmoSearchDialog(
            initialArtist = query.first,
            initialTitle = query.second,
            recognizedArtworkUrl = recognizedArtworkUrl,
            recognitionMessage = if (automaticFallback) "Основной поиск не ответил · резервный источник"
                else recognitionMessage,
            onDismiss = onDismiss,
            onOtherSource = { artist, title ->
                query = artist to title
                reserve = false
                automaticFallback = false
            },
        )
    } else {
        HitMozSearchDialog(
            initialArtist = query.first,
            initialTitle = query.second,
            recognizedArtworkUrl = recognizedArtworkUrl,
            recognitionMessage = recognitionMessage,
            onDismiss = onDismiss,
            onSearchUnavailable = if (source == FullSongSource.AUTO) { artist, title, reason ->
                AppLog.w("MusicSource", "Automatic search fallback · reason=$reason")
                query = artist to title
                reserve = true
                automaticFallback = true
            } else null,
            onOtherSource = { artist, title ->
                query = artist to title
                reserve = true
                automaticFallback = false
            },
        )
    }
}

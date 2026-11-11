package com.domenota.medialoader.ui.screens.music

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType

/** Local-only preview, never alters hidden state or inserts unrelated playlist tracks. */
internal object HiddenMusicPreview {
    fun queue(track: DownloadEntity, hiddenKeys: Set<String>): List<String> {
        if (track.isPrivate || track.hidden || track.mediaType != MediaType.AUDIO ||
            track.state != DownloadState.COMPLETED || track.savedUri.isNullOrBlank() ||
            !MusicTrackVisibility.hidden(track, hiddenKeys)
        ) return emptyList()
        return listOf(track.id)
    }
}

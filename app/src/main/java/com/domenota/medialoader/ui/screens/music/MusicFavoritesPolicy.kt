package com.domenota.medialoader.ui.screens.music

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType

/**
 * Favorites are a property of a library file, NOT of its downloader.
 * Both providerId="device" MediaStore rows and ML downloads are included.
 */
internal object MusicFavoritesPolicy {
    fun select(allAudio: List<DownloadEntity>, favoriteIds: Set<String>): List<DownloadEntity> =
        allAudio.asSequence()
            .filter { it.id in favoriteIds && it.state == DownloadState.COMPLETED &&
                it.mediaType == MediaType.AUDIO && !it.isPrivate && !it.hidden }
            .distinctBy { it.id }
            .toList()
}

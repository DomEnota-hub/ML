package com.domenota.medialoader.data.editor

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType

/**
 * Replacing bytes is only safe when the original and export are both MP3 and
 * the canonical public MediaStore audio URI remains stable. Private encrypted
 * files, SAF aliases and non-MP3 inputs must use ordinary non-destructive copy.
 */
internal object AudioReplacePolicy {
    fun eligible(source: DownloadEntity): Boolean {
        val uri = source.savedUri.orEmpty()
        return source.mediaType == MediaType.AUDIO &&
            source.state == DownloadState.COMPLETED &&
            !source.isPrivate && !source.hidden &&
            source.originalName.endsWith(".mp3", ignoreCase = true) &&
            uri.startsWith("content://media/") &&
            "/audio/media/" in uri
    }
}

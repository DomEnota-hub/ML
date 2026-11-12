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
    /** An app row, a phone alias of an app row, or an independent phone track. */
    data class Target(val row: DownloadEntity, val historyRow: DownloadEntity?)

    fun resolve(source: DownloadEntity, rows: List<DownloadEntity>): Target? {
        if (!eligible(source)) return null
        val keys = com.domenota.medialoader.data.playback.PublicMediaIdentity.uriKeys(source.savedUri!!)
        val matches = rows.filter { row -> row.id == source.id ||
            listOfNotNull(row.savedUri, row.privateSourceUri).any { value ->
                com.domenota.medialoader.data.playback.PublicMediaIdentity.uriKeys(value).any(keys::contains)
            } }
        if (matches.size > 1) return null
        val match = matches.singleOrNull()
        if (match != null) {
            if (!eligible(match) || match.savedUri == null ||
                com.domenota.medialoader.data.playback.PublicMediaIdentity.uriKeys(match.savedUri).none(keys::contains)) return null
            return Target(match.copy(savedUri = source.savedUri), match)
        }
        // Phone-library items are not necessarily downloads. Do not create a fake history row.
        return if (source.providerId == "device") Target(source, null) else null
    }

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

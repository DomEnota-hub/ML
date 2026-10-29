package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.database.DownloadEntity

/** A logical file and every known address for it; contains metadata, never media bytes. */
data class LibraryMediaRef(
    val key: String,
    val aliases: Set<String>,
    val item: DownloadEntity,
    val appDownloadId: String?,
    val deviceAlbumId: String? = null,
    val deviceAlbumName: String? = null,
)

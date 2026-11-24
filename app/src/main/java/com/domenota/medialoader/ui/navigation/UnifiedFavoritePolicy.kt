package com.domenota.medialoader.ui.navigation

import com.domenota.medialoader.core.database.CatalogAssignmentEntity
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.LibraryMediaRef
import com.domenota.medialoader.data.library.collectionRefBlocked
import com.domenota.medialoader.ui.screens.music.MusicTrackVisibility

/** Reuse canonical public refs and the existing assignment store. A favorite
 * alias does not authorize publishing a private/hidden/archived address. */
internal fun publicFavoriteRows(refs:List<LibraryMediaRef>,assignments:List<CatalogAssignmentEntity>,
    blocked:Set<String>,hiddenTracks:Set<String>):List<DownloadEntity>{
    val favorite=assignments.asSequence().filter{it.isFavorite}.map{it.downloadId}.toHashSet()
    return refs.asSequence().filter{ref->
        val row=ref.item
        row.state==DownloadState.COMPLETED&&!row.isPrivate&&!row.hidden&&!row.savedUri.isNullOrBlank()&&
            (ref.key in favorite||row.id in favorite||ref.appDownloadId in favorite||ref.aliases.any(favorite::contains))&&
            !collectionRefBlocked(ref,blocked)&&
            (row.mediaType!=MediaType.AUDIO||!MusicTrackVisibility.hidden(row,hiddenTracks))
    }.distinctBy{it.item.id}.map{it.item}.sortedByDescending{it.createdAtEpochMillis}.toList()
}

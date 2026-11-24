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
    val assigned=assignments.associateBy{it.downloadId}
    return refs.asSequence().filter{ref->
        val row=ref.item
        row.state==DownloadState.COMPLETED&&!row.isPrivate&&!row.hidden&&!row.savedUri.isNullOrBlank()&&
            (ref.appDownloadId?.let(assigned::get) ?: assigned[ref.key] ?: assigned[row.id])?.isFavorite==true&&
            !collectionRefBlocked(ref,blocked)&&
            (row.mediaType!=MediaType.AUDIO||!MusicTrackVisibility.hidden(row,hiddenTracks))
    }.distinctBy{it.item.id}.map{it.item}.sortedByDescending{it.createdAtEpochMillis}.toList()
}

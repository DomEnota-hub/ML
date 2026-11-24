package com.domenota.medialoader.ui.navigation

import com.domenota.medialoader.core.model.MediaType

/** Filtering is presentation only: destructive actions always use exact IDs
 * captured at confirmation and intersected with the current same scope. */
internal fun <T> filterLibraryMedia(rows:List<T>,type:MediaType?,kind:(T)->MediaType):List<T> =
    if(type==null)rows else rows.filter{kind(it)==type}

internal fun confirmedLibraryIds(requested:Set<String>,current:Set<String>):Set<String> =
    requested.intersect(current)

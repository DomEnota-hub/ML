package com.domenota.medialoader.ui

import com.domenota.medialoader.data.library.AppMode

/** Presentation switch only; the repositories continue recording new history and memberships. */
internal object HomeOrganizationPolicy {
    const val PREFERENCE = "home_organization_visible"
    const val DEFAULT_VISIBLE = false
    fun observeCollections(route: String?, mode: AppMode, homeVisible: Boolean) =
        route == "collections" || (route == "home" && (mode == AppMode.GALLERY || homeVisible))
    fun observeReview(route: String?, homeVisible: Boolean) =
        route == "media-review" || (route == "home" && homeVisible)
}

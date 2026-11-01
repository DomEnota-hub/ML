package com.domenota.medialoader.ui.screens.gallery

/**
 * Selection is based on the currently visible, ordered gallery, never on a
 * cached or hidden MediaStore ID. A drag keeps prior selections intact and
 * replaces its own range when the finger moves backward.
 */
internal fun galleryRangeSelection(
    previous: Set<String>,
    visibleIds: List<String>,
    anchorId: String,
    currentId: String,
): Set<String> {
    val start = visibleIds.indexOf(anchorId)
    val end = visibleIds.indexOf(currentId)
    if (start == -1 || end == -1) return previous.intersect(visibleIds.toSet())
    val range = visibleIds.subList(minOf(start, end), maxOf(start, end) + 1)
    return previous.intersect(visibleIds.toSet()) + range
}

/** Only the visible rows belonging to the requested day are affected. */
internal fun toggleGalleryDaySelection(
    selected: Set<String>,
    visibleIds: List<String>,
    dayIds: List<String>,
): Set<String> {
    val visible = visibleIds.toHashSet()
    val current = selected.intersect(visible)
    val day = dayIds.filter { it in visible }.toSet()
    if (day.isEmpty()) return current
    return if (day.all { it in current }) current - day else current + day
}

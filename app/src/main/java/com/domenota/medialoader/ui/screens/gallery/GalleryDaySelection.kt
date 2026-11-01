package com.domenota.medialoader.ui.screens.gallery

/**
 * Select visible media from one day only, without touching already selected
 * items from other days. A second press when all visible day items are
 * selected deselects only that day.
 *
 * Selection is kept in memory by the screen: do not serialize all IDs into
 * Android SavedState for galleries containing thousands of files.
 */
internal object GalleryDaySelection {
    fun toggleDay(selectedIds: List<String>, visibleDayIds: List<String>): ArrayList<String> {
        if (visibleDayIds.isEmpty()) return ArrayList(selectedIds)
        val day = visibleDayIds.toHashSet()
        val selected = selectedIds.toHashSet()
        if (day.all { it in selected }) {
            return ArrayList(selectedIds.filterNot { it in day })
        }
        return ArrayList(selectedIds + visibleDayIds.filterNot { it in selected })
    }

    fun selectedCount(selectedIds: Set<String>, visibleDayIds: List<String>): Int =
        visibleDayIds.count { it in selectedIds }
}

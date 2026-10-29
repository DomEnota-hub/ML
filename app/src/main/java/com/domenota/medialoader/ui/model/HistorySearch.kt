package com.domenota.medialoader.ui.model

import java.util.Locale

/** Search local download history only. Active queue rows remain visible and untouched. */
object HistorySearch {
    fun matches(item: DownloadUiItem, query: String): Boolean {
        val needle = query.trim().lowercase(Locale.ROOT)
        if (needle.isBlank()) return true
        return listOfNotNull(
            item.title,
            item.recognizedArtist,
            item.recognizedTitle,
            item.recognitionLabel,
        ).any { it.lowercase(Locale.ROOT).contains(needle) }
    }

    /** Preserve the original groups, but show only matching children. */
    fun filterGroups(groups: List<List<DownloadUiItem>>, query: String): List<List<DownloadUiItem>> =
        if (query.isBlank()) groups else groups.mapNotNull { group ->
            group.filter { matches(it, query) }.takeIf { it.isNotEmpty() }
        }
}

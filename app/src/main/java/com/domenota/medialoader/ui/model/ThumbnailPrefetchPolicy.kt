package com.domenota.medialoader.ui.model

internal object ThumbnailPrefetchPolicy {
    fun indices(first: Int, last: Int, previousFirst: Int, total: Int): List<Int> {
        val after = (last + 1..last + 8).toList()
        val before = (first - 1 downTo first - 4).toList()
        return (if (first < previousFirst) before + after else after + before)
            .filter { it in 0 until total }.distinct()
    }
}

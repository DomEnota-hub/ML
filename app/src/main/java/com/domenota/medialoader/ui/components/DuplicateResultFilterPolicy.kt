package com.domenota.medialoader.ui.components

/**
 * A duplicate category is meaningful only when at least one verified or
 * provisional group exists. Never expose zero-count filters in an empty state.
 * The selection is derived rather than persisted into the scan/session model.
 */
internal object DuplicateResultFilterPolicy {
    fun <K> counts(kinds: Iterable<K>): Map<K, Int> = kinds.groupingBy { it }.eachCount()

    fun <K> available(ordered: List<K>, counts: Map<K, Int>): List<K> =
        ordered.filter { (counts[it] ?: 0) > 0 }

    /** Old selections become "All" if their category vanished on refresh. */
    fun <K> active(selected: K?, counts: Map<K, Int>): K? =
        selected?.takeIf { (counts[it] ?: 0) > 0 }

    fun <K> hasResults(counts: Map<K, Int>): Boolean = counts.values.any { it > 0 }
}

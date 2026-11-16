package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType

/** The scan scope is fixed for a run. Existing reports reconcile with all
 * accessible public visual media, not a narrower currently selected source. */
internal data class VisualDuplicateCatalog(val all: List<LibraryMediaRef>, val scan: List<LibraryMediaRef>, val source: LibrarySource) {
    companion object {
        val EMPTY = VisualDuplicateCatalog(emptyList(), emptyList(), LibrarySource.PHONE)
        fun candidates(refs: List<LibraryMediaRef>): List<LibraryMediaRef> = refs.filter {
            val row = it.item
            !row.isPrivate && !row.hidden && row.state == DownloadState.COMPLETED &&
                (row.mediaType == MediaType.PHOTO || row.mediaType == MediaType.VIDEO) && !row.savedUri.isNullOrBlank()
        }.distinctBy { it.item.savedUri }
        fun build(refs: List<LibraryMediaRef>, appItems: List<DownloadEntity>, source: LibrarySource): VisualDuplicateCatalog {
            val all = candidates(refs)
            val scan = if (source == LibrarySource.PHONE) all else {
                val ids = appItems.mapTo(HashSet()) { it.id }
                all.filter { it.item.id in ids }
            }
            return VisualDuplicateCatalog(all, scan, source)
        }
    }
}

/** Counters partition the *same* candidate population. A cached fingerprint
 * counts as checked; aliases are not reported as independent duplicate files. */
internal data class VisualScanCoverage(val source: LibrarySource, val total: Int,
    val cached: Int = 0, val computed: Int = 0, val aliases: Int = 0, val failed: Int = 0,
    val incompleteVisual: Int = 0) {
    val checked: Int get() = cached + computed + aliases + failed
    init { require(total >= 0 && listOf(cached, computed, aliases, failed, incompleteVisual).all { it >= 0 } &&
        checked <= total && incompleteVisual <= computed) }
    fun record(outcome: VisualScanOutcome, incomplete: Boolean = false): VisualScanCoverage = when (outcome) {
        VisualScanOutcome.CACHED -> copy(cached = cached + 1)
        VisualScanOutcome.COMPUTED -> copy(computed = computed + 1, incompleteVisual = incompleteVisual + if (incomplete) 1 else 0)
        VisualScanOutcome.ALIAS -> copy(aliases = aliases + 1)
        VisualScanOutcome.FAILED -> copy(failed = failed + 1)
    }
}
internal enum class VisualScanOutcome { CACHED, COMPUTED, ALIAS, FAILED }

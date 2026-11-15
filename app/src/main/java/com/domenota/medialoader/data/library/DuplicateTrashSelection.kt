package com.domenota.medialoader.data.library

/** Snapshot of an explicit trash confirmation; rejected groups are never sent to the provider. */
internal data class DuplicateTrashSelection(
    val groups: List<DuplicateGroup>, val selected: Set<String>, val verified: Set<String>,
    val failures: Map<String,String>,
)

/** Terminal reports must account for every explicitly requested ID. */
internal fun DuplicateTrashSelection.unaccounted(outcomes:Set<String>):Set<String> = selected-outcomes

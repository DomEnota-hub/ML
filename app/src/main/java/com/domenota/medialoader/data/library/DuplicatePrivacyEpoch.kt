package com.domenota.medialoader.data.library

/** Bounded epoch invalidation: no media identities or content are persisted here. */
internal class DuplicatePrivacyEpoch(initial: Long = 0) {
    var generation: Long = initial
        private set
    private val blocked = mutableMapOf<String,Set<String>>()
    fun accepts(epoch: Long, id: String, identities: Set<String> = emptySet()) = epoch == generation && id !in blocked &&
        blocked.values.none { aliases -> identities.any { it in aliases } }
    fun revoke(id: String, identities: Set<String> = emptySet()) { generation++; blocked[id] = identities.toSet() }
    fun restored(id: String) { blocked.remove(id) }
}

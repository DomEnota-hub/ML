package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.model.DownloadState

/** Linear identity grouping: duplicate names or file sizes are deliberately not identities. */
object UnifiedMediaLibrary {
    fun merge(app: List<LibraryMediaRef>, phone: List<LibraryMediaRef>, privateKeys: Set<String>): List<LibraryMediaRef> {
        val refs = app + phone
        val parents = IntArray(refs.size) { it }
        fun root(index: Int): Int {
            var current = index
            while (parents[current] != current) {
                parents[current] = parents[parents[current]]
                current = parents[current]
            }
            return current
        }
        val owners = HashMap<String, Int>()
        refs.forEachIndexed { index, ref ->
            fun connect(alias: String) {
                val previous = owners.putIfAbsent(alias, index)
                if (previous != null) parents[root(index)] = root(previous)
            }
            ref.aliases.forEach(::connect)
            if (ref.key !in ref.aliases) connect(ref.key)
            if (ref.item.id != ref.key && ref.item.id !in ref.aliases) connect(ref.item.id)
        }
        return refs.indices.groupBy(::root).values.mapNotNull { indices ->
            val group = indices.map { refs[it] }
            val aliases = buildSet { group.forEach { addAll(it.aliases); add(it.key); add(it.item.id) } }
            if (group.any { it.item.isPrivate } || aliases.any { it in privateKeys }) return@mapNotNull null
            val primary = group.asSequence().filter { !it.item.hidden && it.item.state == DownloadState.COMPLETED && !it.item.savedUri.isNullOrBlank() }
                .minWithOrNull(compareBy<LibraryMediaRef> { it.appDownloadId == null }.thenBy { it.key })
                ?: return@mapNotNull null
            primary.copy(aliases = aliases,
                deviceAlbumId = group.firstNotNullOfOrNull { it.deviceAlbumId },
                deviceAlbumName = group.firstNotNullOfOrNull { it.deviceAlbumName })
        }.sortedWith(compareByDescending<LibraryMediaRef> { it.item.createdAtEpochMillis }.thenBy { it.key })
    }
}

package com.domenota.medialoader.data.catalog.visibility

/** Independent, read-only privacy proof for the selected Archive.
 * UNKNOWN is the initial state and the response to corrupt/unreadable journals.
 * It must never be interpreted as "there are no archived files". */
internal data class SelectiveArchivePrivacySnapshot(
    val verified: Boolean = false,
    val blockedIds: Set<String> = emptySet(),
    val blockedUris: Set<String> = emptySet(),
    val blockedAliases: Set<String> = emptySet(),
)

/** A PREPARED journal cannot have mutated MediaStore; HIDE_INTENT is written
 * before the first mutation and remains hidden even after process death.
 * Restored items become eligible for ordinary public-index identity checks. */
internal fun selectiveArchiveBlocksPublic(phase: SelectiveArchiveMovePhase?): Boolean =
    phase in setOf(
        SelectiveArchiveMovePhase.HIDE_INTENT,
        SelectiveArchiveMovePhase.HIDDEN_VERIFIED,
        SelectiveArchiveMovePhase.RESTORE_INTENT,
        SelectiveArchiveMovePhase.NEEDS_REVIEW,
    )

internal fun archivePrivacyProjection(
    entries: List<Pair<SelectiveArchiveIdentity, SelectiveArchiveMovePhase?>>,
): SelectiveArchivePrivacySnapshot {
    val blocked=entries.filter { selectiveArchiveBlocksPublic(it.second) }.map { it.first }
    require(blocked.all { it.wellFormed() } &&
        blocked.map { it.catalogId }.distinct().size==blocked.size &&
        blocked.map { it.originalUri }.distinct().size==blocked.size) {
        "Нельзя подтвердить уникальность записей Архива"
    }
    val aliases=buildSet {
        for(row in blocked) {
            add(row.catalogId)
            add("app:${row.catalogId}")
            add(row.originalUri)
            add("uri:${row.originalUri}")
            val uri=row.originalUri
            val kind=when {
                "/images/media/" in uri -> "image"
                "/video/media/" in uri -> "video"
                "/audio/media/" in uri -> "audio"
                else -> null
            }
            if(kind!=null) {
                add("phone:$kind:${row.mediaStoreId}")
                add("media:${row.volume}:${row.mediaStoreId}")
                if(row.volume=="external_primary") {
                    add("media:external:${row.mediaStoreId}")
                    val legacy=uri.replace("/external_primary/","/external/")
                    add(legacy)
                    add("uri:$legacy")
                }
            }
        }
    }
    return SelectiveArchivePrivacySnapshot(
        verified=true,
        blockedIds=blocked.mapTo(mutableSetOf()) { it.catalogId },
        blockedUris=blocked.mapTo(mutableSetOf()) { it.originalUri },
        blockedAliases=aliases,
    )
}

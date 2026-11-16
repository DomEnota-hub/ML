package com.domenota.medialoader.ui

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.data.library.LibraryMediaRef
import com.domenota.medialoader.data.library.collectionRefBlocked
import com.domenota.medialoader.data.storage.SavedFileStatus

/** Semantic access keys, separate from ordinary library revisions. */
internal data class CollectionAccessGuard(val privacy: Set<String>, val blocked: Set<String>,
    val galleryEpoch: Long, val musicEpoch: Long, val permissionTag: String = "", val privacyGeneration: Long = 0L)
internal data class CollectionDetailGuard(val access: CollectionAccessGuard, val id: String)
internal data class RecentAccessGuard(val access: AudioCatalogGuard, val blocked: Set<String>, val source: com.domenota.medialoader.data.library.LibrarySource)
internal data class RouteMediaCatalog(val refs: List<LibraryMediaRef>) {
    val byKey = refs.associateBy { it.key }
    val items = refs.map { it.item }
    companion object { val EMPTY = RouteMediaCatalog(emptyList()) }
}

/** Filter a generation with current history/status on the background projection, not
 * in root composition. Status from an old URI cannot suppress a recovered file. */
internal fun filterRouteCatalog(refs: List<LibraryMediaRef>, blocked: Set<String>, history: Map<String, DownloadEntity>,
    fileStatuses: Map<String, SavedFileStatus>, fileStatusUris: Map<String, String?>): List<LibraryMediaRef> = refs.filter { ref ->
    !collectionRefBlocked(ref, blocked) &&
        (ref.appDownloadId == null || history[ref.appDownloadId]?.let { current ->
            !current.isPrivate && !current.hidden && current.savedUri == ref.item.savedUri && current.state == DownloadState.COMPLETED
        } == true) && (fileStatusUris[ref.key] != ref.item.savedUri ||
        (fileStatuses[ref.key] != SavedFileStatus.MISSING && fileStatuses[ref.key] != SavedFileStatus.INACCESSIBLE))
}

/** Browsing/playing a saved collection never demands an enumerated phone catalog.
 * A media/cover picker or explicit legacy lookup may request one. */
internal enum class CollectionCatalogRequest { NONE, AUDIO, ALL }
internal fun collectionScanModes(request: CollectionCatalogRequest): Set<com.domenota.medialoader.data.library.AppMode> = when(request) {
    CollectionCatalogRequest.NONE -> emptySet()
    CollectionCatalogRequest.AUDIO -> setOf(com.domenota.medialoader.data.library.AppMode.MUSIC)
    CollectionCatalogRequest.ALL -> setOf(com.domenota.medialoader.data.library.AppMode.GALLERY, com.domenota.medialoader.data.library.AppMode.MUSIC)
}

/** A displayed cached row is never a live authority. Current Room identity, member
 * membership, known absence and privacy win synchronously, even during a retry. */
internal fun filterAddressedDisplay(value: com.domenota.medialoader.data.library.AddressedCollection,
    members: List<com.domenota.medialoader.core.database.MediaCollectionMemberEntity>, history: Map<String, DownloadEntity>,
    blocked: Set<String>, statuses: Map<String, SavedFileStatus>, statusUris: Map<String, String?>): com.domenota.medialoader.data.library.AddressedCollection {
    val ids = members.associateBy { it.id }
    val resolved = value.resolved.filter { (id, ref) ->
        val member = ids[id]
        member != null && value.checkedMembers[id] == member.identityJson &&
            !com.domenota.medialoader.data.library.collectionMemberBlocked(member, blocked) &&
            !collectionRefBlocked(ref, blocked) &&
            (ref.appDownloadId == null || history[ref.appDownloadId]?.let {
                !it.hidden && !it.isPrivate && it.state == DownloadState.COMPLETED && it.savedUri == ref.item.savedUri
            } == true) && !(statusUris[ref.key] == ref.item.savedUri &&
                statuses[ref.key] in setOf(SavedFileStatus.MISSING, SavedFileStatus.INACCESSIBLE))
    }
    if (resolved.size == value.resolved.size) return value
    val removed = value.resolved.keys - resolved.keys
    val knownAbsent = removed.filter { id -> value.resolved[id]?.let { ref ->
        statusUris[ref.key] == ref.item.savedUri && statuses[ref.key] in setOf(SavedFileStatus.MISSING, SavedFileStatus.INACCESSIBLE)
    } == true }.toSet()
    val suppressed = members.filter { member ->
        com.domenota.medialoader.data.library.collectionMemberBlocked(member, blocked) || value.resolved[member.id]?.let { collectionRefBlocked(it, blocked) } == true
    }.mapTo(mutableSetOf()) { it.id }
    return value.copy(refs = resolved.values.distinctBy { it.key }, resolved = resolved,
        pending = (value.pending + removed.filter { it in ids }) - knownAbsent - suppressed,
        suppressed = value.suppressed + suppressed, invalidated = value.invalidated + removed)
}

/** Address invalidation is independent of completion of a phone-wide scan. */
internal data class CollectionMediaSignal(val revision: Long = 0, val uris: Set<String>? = emptySet(),
    val modes: Set<com.domenota.medialoader.data.library.AppMode> = emptySet()) {
    fun affects(members: List<com.domenota.medialoader.core.database.MediaCollectionMemberEntity>, history: Map<String,DownloadEntity>): Boolean {
        if(revision==0L) return false
        val files=members.filter { it.link==null }
        if(files.isEmpty()) return false
        val addresses=files.flatMap { com.domenota.medialoader.data.library.CollectionAddressResolver.phoneAddresses(it) } + history.values.mapNotNull { it.savedUri }
        val hasUnknown=files.any { member ->
            com.domenota.medialoader.data.library.CollectionAddressResolver.phoneAddresses(member).isEmpty() &&
                com.domenota.medialoader.data.library.MediaOrganizationPolicy.decode(member.identityJson).none { it.startsWith("app:") && history.containsKey(it.removePrefix("app:")) }
        }
        if(uris!=null && !hasUnknown) return addresses.any(uris::contains)
        return hasUnknown || addresses.any { address -> phoneModesForMediaChange(address).any(modes::contains) }
    }
}

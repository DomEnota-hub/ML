package com.domenota.medialoader.ui

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.database.MediaCollectionMemberEntity
import com.domenota.medialoader.data.library.*
import com.domenota.medialoader.data.storage.SavedFileStatus

/** A known exact folder batch may keep materialized public reads, never writes. */
internal fun collectionReadAllowed(publicAccess: Boolean, recovering: Boolean, pendingFolder: String?, unknown: Boolean,
    preparingFolderOperation: Boolean = false): Boolean =
    publicAccess && !unknown && (!recovering || pendingFolder != null || preparingFolderOperation)

/** Metadata only. The temporary index contains requested member keys, not a full
 * catalog index. No provider lookup, saved-metadata admission or deep merge. */
internal fun materializedCollectionRead(members: List<MediaCollectionMemberEntity>, refs: List<LibraryMediaRef>,
    history: Map<String, DownloadEntity>, blocked: Set<String>, statuses: Map<String, SavedFileStatus>,
    statusUris: Map<String, String?>): AddressedCollection {
    val files = members.filter { it.link == null }
    val suppressed = files.filter { collectionMemberBlocked(it, blocked) }.mapTo(mutableSetOf()) { it.id }
    val allowed = filterRouteCatalog(refs, blocked, history, statuses, statusUris)
    val byId = files.associateBy { it.id }
    val resolved = MediaOrganizationPolicy.resolve(files.filter { it.id !in suppressed }, allowed).filter { (id, ref) ->
        val member = byId.getValue(id)
        CollectionAddressResolver.matches(MediaOrganizationPolicy.decode(member.identityJson), ref)
    }
    return AddressedCollection(resolved.values.distinctBy { it.key },
        files.mapTo(mutableSetOf()) { it.id } - resolved.keys - suppressed, emptyMap(), suppressed,
        files.associate { it.id to it.identityJson }, resolved)
}

/** Synchronous removal only while the worker rebuilds. A retained locator must
 * still be present under the current public guard and the exact persisted member. */
internal fun retainMaterializedCollectionRead(previous: AddressedCollection?, members: List<MediaCollectionMemberEntity>,
    current: Map<String, LibraryMediaRef>, history: Map<String, DownloadEntity>, blocked: Set<String>,
    statuses: Map<String, SavedFileStatus>, statusUris: Map<String, String?>): AddressedCollection? {
    previous ?: return null
    val byId = members.associateBy { it.id }
    val resolved = previous.resolved.mapNotNull { (id, old) ->
        val member = byId[id] ?: return@mapNotNull null
        val ref = current[old.key] ?: return@mapNotNull null
        if (previous.checkedMembers[id] != member.identityJson || old.item.savedUri != ref.item.savedUri ||
            !CollectionAddressResolver.matches(MediaOrganizationPolicy.decode(member.identityJson), ref)) return@mapNotNull null
        id to ref
    }.toMap()
    val base = AddressedCollection.unconfirmed(members, blocked)
    return filterAddressedDisplay(base.copy(refs=resolved.values.distinctBy { it.key }, resolved=resolved,
        pending=base.pending - resolved.keys), members, history, blocked, statuses, statusUris)
}

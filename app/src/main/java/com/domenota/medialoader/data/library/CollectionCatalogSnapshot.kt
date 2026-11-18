package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.database.MediaCollectionMemberEntity
import com.domenota.medialoader.core.model.DownloadState

/** Only a complete generation replaces the last public catalog. A privacy/permission
 * invalidation discards it, even when the next generation is still incomplete. */
internal data class CollectionCatalogSnapshot(val refs: List<LibraryMediaRef>, val privacy: Set<String>,
    val identityIndex: CollectionIdentityIndex) {
    val byKey = refs.associateBy { it.key }
    companion object {
        fun select(previous: CollectionCatalogSnapshot?, incoming: List<LibraryMediaRef>,
            complete: Boolean, permitted: Boolean, privacy: Set<String>?, index: CollectionIdentityIndex? = null): CollectionCatalogSnapshot? {
            if (!permitted || privacy == null) return null
            return if (complete) CollectionCatalogSnapshot(incoming, privacy, index ?: CollectionIdentityIndex.build(incoming))
                else previous?.takeIf { it.privacy == privacy }
        }
    }
}

internal fun collectionRefBlocked(ref: LibraryMediaRef, blocked: Set<String>): Boolean =
    ref.item.isPrivate || ref.item.hidden || ref.key in blocked || ref.item.id in blocked ||
        ref.appDownloadId?.let(blocked::contains) == true ||
        ref.item.savedUri?.let { "uri:$it" in blocked } == true || ref.aliases.any(blocked::contains)

internal fun collectionMemberBlocked(member: MediaCollectionMemberEntity, blocked: Set<String>): Boolean =
    MediaOrganizationPolicy.decode(member.identityJson).any { key ->
        key in blocked || (key.startsWith("app:") && key.removePrefix("app:") in blocked) ||
            (key.startsWith("locator:") && blocked.any { key.startsWith("locator:$it:") })
    }

/** History metadata is only associated by a saved app identity, never by title. */
internal fun collectionMissingHistory(member: MediaCollectionMemberEntity, history: Map<String, DownloadEntity>,
    blocked: Set<String>): DownloadEntity? {
    if (collectionMemberBlocked(member, blocked)) return null
    val id = MediaOrganizationPolicy.decode(member.identityJson).filter { it.startsWith("app:") }.singleOrNull()
        ?.removePrefix("app:") ?: return null
    return history[id]?.takeIf { !it.isPrivate && !it.hidden && it.state == DownloadState.COMPLETED &&
        it.id !in blocked && it.savedUri?.let { uri -> "uri:$uri" !in blocked } != false }
}

/** Constructed on the existing library IO pass, not while composing a collection. */
internal class CollectionIdentityIndex private constructor(private val index: Map<String, List<LibraryMediaRef>>) {
    fun candidates(member: MediaCollectionMemberEntity): List<LibraryMediaRef> =
        MediaOrganizationPolicy.decode(member.identityJson).flatMap { index[it].orEmpty() }.distinctBy { it.key }
    fun resolve(members: List<MediaCollectionMemberEntity>, allowed: Map<String, LibraryMediaRef>): Map<String, LibraryMediaRef> =
        members.mapNotNull { member ->
            val match = candidates(member).singleOrNull() ?: return@mapNotNull null
            allowed[match.key]?.takeIf { it.item.savedUri == match.item.savedUri }?.let { member.id to it }
        }.toMap()
    companion object {
        fun build(refs: List<LibraryMediaRef>): CollectionIdentityIndex = CollectionIdentityIndex(buildMap<String, MutableList<LibraryMediaRef>> {
            refs.forEach { ref -> MediaOrganizationPolicy.keys(ref).forEach { key -> getOrPut(key) { mutableListOf() }.add(ref) } }
        })
    }
}

/** Custom cover files are copies of public source images, not an exemption from privacy. */
internal fun collectionCoverAllowed(source: String, blocked: Set<String>?): Boolean = blocked != null &&
    com.domenota.medialoader.data.playback.PublicMediaIdentity.uriKeys(source).none(blocked::contains)

/** Explicit, immutable cover access input. A resumed permission change invalidates
 * an otherwise unchanged card; app-owned public source photos need not be members. */
internal data class CollectionCoverAccess(val fullPhotoAccess: Boolean, val appPhotoUris: Set<String>,
    val snapshotPrivacy: Set<String>? = null, val sourceRevision: Long = 0L) {
    fun permits(source: String, blocked: Set<String>?): Boolean =
        collectionCoverAllowed(source, blocked) && (fullPhotoAccess || source in appPhotoUris)
}

/** The first public media slot in persisted collection order owns the automatic cover.
 * Resolve that slot only: partial catalogs, loading and failed lookups must not promote
 * a later song/video. Explicit reorder/removal changes the slot; display filters do not.
 * The id tie-break matches Room's ordering, including legacy members without sortOrder. */
internal fun collectionAutomaticCoverMember(members: List<MediaCollectionMemberEntity>, blocked: Set<String>?): MediaCollectionMemberEntity? {
    if (blocked == null) return null
    return members.asSequence().filter { it.link == null && !collectionMemberBlocked(it, blocked) }
        .minWithOrNull(compareBy<MediaCollectionMemberEntity> { it.sortOrder ?: it.addedAt }.thenBy { it.id })
}

/** A cached thumbnail is eligible only for the same authoritative slot and identity. */
internal fun collectionAutomaticCover(members: List<MediaCollectionMemberEntity>, resolved: Map<String, LibraryMediaRef>,
    blocked: Set<String>?, cached: LibraryMediaRef? = null): LibraryMediaRef? {
    val member = collectionAutomaticCoverMember(members, blocked) ?: return null
    fun allowed(ref: LibraryMediaRef) = !collectionRefBlocked(ref, requireNotNull(blocked)) &&
        CollectionAddressResolver.matches(MediaOrganizationPolicy.decode(member.identityJson), ref)
    return resolved[member.id]?.takeIf(::allowed) ?: cached?.takeIf(::allowed)
}

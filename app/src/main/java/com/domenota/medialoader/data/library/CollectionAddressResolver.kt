package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.database.MediaCollectionMemberEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.data.storage.SavedFileStatus
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** One generation for the selected collection. Pending identities are not missing files. */
internal data class AddressedCollection(val refs: List<LibraryMediaRef>, val pending: Set<String>,
    val statuses: Map<String, Pair<String?, SavedFileStatus>>, val suppressed: Set<String> = emptySet(),
    val checkedMembers: Map<String, String?> = emptyMap(), val resolved: Map<String, LibraryMediaRef> = emptyMap(),
    val invalidated: Set<String> = emptySet()) {
    val byKey = refs.associateBy { it.key }
    companion object { val EMPTY = AddressedCollection(emptyList(), emptySet(), emptyMap()) }
}
internal data class AddressedFile(val ref: LibraryMediaRef?, val status: SavedFileStatus)

/** Only an ordinary same-access refresh may keep an already validated member when
 * its new lookup is inconclusive. Confirmed absence, privacy, changed identity and
 * recycled/ambiguous addresses always win over the retained display snapshot. */
internal fun retainAddressedCollection(previous: AddressedCollection?, incoming: AddressedCollection): AddressedCollection {
    if (previous == null || incoming.pending.isEmpty()) return incoming
    val retained = mutableMapOf<String, LibraryMediaRef>()
    val refs = incoming.byKey.toMutableMap()
    for (id in incoming.pending) {
        if (id in incoming.suppressed || id in incoming.invalidated || !incoming.checkedMembers.containsKey(id) ||
            incoming.checkedMembers[id] != previous.checkedMembers[id]) continue
        val old = previous.resolved[id] ?: continue
        val status = old.appDownloadId?.let(incoming.statuses::get)
        if (status != null && (status.first != old.item.savedUri || status.second == SavedFileStatus.MISSING ||
                status.second == SavedFileStatus.INACCESSIBLE)) continue
        val aliases = old.aliases
        val collisions = refs.values.filter { it.key == old.key || it.aliases.any(aliases::contains) }
        val ref = if (collisions.isEmpty()) old else collisions.singleOrNull()?.takeIf {
            CollectionAddressResolver.matches(MediaOrganizationPolicy.decode(incoming.checkedMembers[id]),it)
        } ?: continue
        refs[ref.key] = ref
        retained[id] = ref
    }
    return if (retained.isEmpty()) incoming else incoming.copy(refs = refs.values.toList(), resolved = incoming.resolved + retained)
}

/** Only a stored, typed MediaStore locator is eligible for direct lookup. Its size/date or
 * physical identity must still match the saved member; a recycled URI alone is insufficient. */
internal object CollectionAddressResolver {
    private val mediaLocator = Regex("^locator:uri:(content://media/[^/:]+/(?:audio|video|images)/media/[0-9]+):[0-9]+:[0-9]+$")
    fun phoneAddresses(member: MediaCollectionMemberEntity): List<String> =
        MediaOrganizationPolicy.decode(member.identityJson).mapNotNull { mediaLocator.matchEntire(it)?.groupValues?.get(1) }.distinct()

    internal fun matches(keys: Set<String>, ref: LibraryMediaRef): Boolean {
        val candidateKeys = MediaOrganizationPolicy.keys(ref)
        if (ref.appDownloadId?.let { "app:$it" in keys } == true) return true
        val savedPhysical = keys.filter { it.startsWith("file-stat:") }
        val currentPhysical = candidateKeys.filter { it.startsWith("file-stat:") }
        // A changed inode/size/mtime is not the same file just because MediaStore
        // recycled the URI and the name/date happen to be identical.
        if (savedPhysical.isNotEmpty() && currentPhysical.isNotEmpty()) return savedPhysical.any(currentPhysical::contains)
        return candidateKeys.any(keys::contains)
    }

    suspend fun resolve(members: List<MediaCollectionMemberEntity>, history: Map<String, DownloadEntity>,
        fallback: CollectionCatalogSnapshot?, fullCurrent: Boolean, blocked: Set<String>,
        app: suspend (DownloadEntity) -> AddressedFile,
        phone: suspend (String) -> AddressedFile,
        hidden: (LibraryMediaRef) -> Boolean = { false }): AddressedCollection {
        val pending = mutableSetOf<String>()
        val invalidated = mutableSetOf<String>()
        val suppressed = mutableSetOf<String>()
        val statuses = mutableMapOf<String, Pair<String?, SavedFileStatus>>()
        val refs = mutableListOf<LibraryMediaRef>()
        val accepted = mutableSetOf<String>()
        val fallbackRefs = fallback?.byKey.orEmpty()
        val fallbackMatches = fallback?.identityIndex?.resolve(members, fallbackRefs).orEmpty()
        val queriedApp = mutableMapOf<String, AddressedFile>()
        val queriedPhone = mutableMapOf<String, AddressedFile>()
        val wantedAddresses = members.flatMap(::phoneAddresses).toHashSet()
        val historyAtAddress = if (wantedAddresses.isEmpty()) emptyMap() else history.values
            .filter { it.savedUri in wantedAddresses }.groupBy { it.savedUri }
        for (member in members) {
            coroutineContext.ensureActive()
            if (member.link != null) continue
            if (collectionMemberBlocked(member, blocked)) { suppressed += member.id; continue }
            val keys = MediaOrganizationPolicy.decode(member.identityJson)
            val appIds = keys.filter { it.startsWith("app:") }.map { it.removePrefix("app:") }
            val historyRow = appIds.singleOrNull()?.let(history::get)
            if (historyRow?.let { it.isPrivate || it.hidden } == true) { suppressed += member.id; continue }
            val candidateRefs = mutableListOf<LibraryMediaRef>()
            var knownUnavailable = false
            suspend fun considerApp(row: DownloadEntity) {
                if (row.isPrivate || row.hidden || row.state != DownloadState.COMPLETED || row.savedUri.isNullOrBlank()) return
                val result = queriedApp[row.id] ?: app(row).also { queriedApp[row.id] = it }
                statuses[row.id] = row.savedUri to result.status
                if (result.status == SavedFileStatus.PRESENT) result.ref?.let(candidateRefs::add)
                else if (result.status == SavedFileStatus.MISSING || result.status == SavedFileStatus.INACCESSIBLE) knownUnavailable = true
            }
            if (historyRow != null) considerApp(historyRow)
            // For an app member, its current authoritative ID is sufficient. Don't attach
            // an old URI now occupied by a different file. Unknown/legacy members need the
            // exact locator, or the ordinary full identity index as a fallback.
            if (candidateRefs.isEmpty()) {
                val fallbackRef = fallbackMatches[member.id]
                val fallbackApp = fallbackRef?.appDownloadId?.let(history::get)
                if (fallbackApp != null) considerApp(fallbackApp)
                val addresses = ((if (historyRow == null) phoneAddresses(member) else emptyList()) +
                    listOfNotNull(fallbackRef?.takeIf { it.appDownloadId == null }?.item?.savedUri)).distinct()
                for (uri in addresses) {
                    val result = queriedPhone[uri] ?: phone(uri).also { queriedPhone[uri] = it }
                    if (result.status == SavedFileStatus.PRESENT) result.ref?.let { phoneRef ->
                        candidateRefs += phoneRef
                        // Prefer the same authoritative app row when the exact phone
                        // locator also belongs to ML, including before the full index.
                        historyAtAddress[uri]?.singleOrNull()?.takeIf { it.mediaType == phoneRef.item.mediaType }
                            ?.let { row ->
                                if (row.isPrivate || row.hidden) suppressed += member.id
                                else considerApp(row)
                            }
                    } else if (result.status == SavedFileStatus.MISSING || result.status == SavedFileStatus.INACCESSIBLE) {
                        knownUnavailable = true
                        invalidated += member.id
                    }
                }
            }
            // All candidates must match a saved identity, not merely the current address.
            val candidates = candidateRefs.filter { ref -> matches(keys, ref) }
            if (member.id in suppressed || candidates.any { collectionRefBlocked(it, blocked) || hidden(it) }) {
                suppressed += member.id
                continue
            }
            val conflicting = fullCurrent && (fallback?.identityIndex?.candidates(member)?.size ?: 0) > 1
            val unique = UnifiedMediaLibrary.merge(candidates, emptyList(), blocked).singleOrNull().takeUnless { conflicting }
            if (unique != null) { refs += unique; accepted += member.id }
            else {
                if (candidateRefs.isNotEmpty() || conflicting) invalidated += member.id
                if (!knownUnavailable && !fullCurrent) pending += member.id
            }
        }
        val merged = UnifiedMediaLibrary.merge(refs.distinctBy { it.key }, emptyList(), blocked)
        val resolved = CollectionIdentityIndex.build(merged).resolve(members.filter { it.id in accepted }, merged.associateBy { it.key })
        return AddressedCollection(merged, pending, statuses, suppressed, members.associate { it.id to it.identityJson }, resolved, invalidated)
    }
}

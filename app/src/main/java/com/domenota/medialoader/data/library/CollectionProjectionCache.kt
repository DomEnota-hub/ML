package com.domenota.medialoader.data.library

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.database.MediaCollectionMemberEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A last confirmed collection projection. Current Room membership and privacy are
 * authoritative. External rows are reusable only with full access and the same
 * MediaStore version/generation; selected-only grants never reuse disk rows. */
internal class CollectionProjectionCache(context: Context, namespace: String = "collections") {
    private val app = context.applicationContext
    private val cache = PersistentProjectionCache(app, namespace)
    private val phone = PhoneMediaLibrary(app)
    private fun fullAccess(type: MediaType) = PhoneMediaLibrary.canReadMissingMetadata(type, Build.VERSION.SDK_INT) {
        ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED
    }
    fun guard(blocked: Set<String>): String = PhoneLibraryCache.privacyTag(blocked +
        MediaType.entries.map { "permission:${it.name}:${fullAccess(it)}" } +
        "privacy-epoch:${DuplicatePrivacyGuard.generation(app)}")

    suspend fun read(id: String, members: List<MediaCollectionMemberEntity>, history: Map<String, DownloadEntity>,
        blocked: Set<String>, currentStamp: PhoneStoreStamp? = null): AddressedCollection? {
        val expectedGuard = guard(blocked)
        val root = cache.read(id, expectedGuard) ?: return null
        return try {
            val entries = root.getJSONObject("members")
            val hasExternal = entries.keys().asSequence().any { entries.optJSONObject(it)?.isNull("app") != false }
            val stamp = if (hasExternal) currentStamp ?: phone.storeStamp() else PhoneStoreStamp(null, null)
            val externalCurrent = stamp.versions != null && stamp.generation != null &&
                root.optString("versions") == stamp.versions && root.optString("generation") == stamp.generation
            val resolved = linkedMapOf<String, LibraryMediaRef>()
            val pending = linkedSetOf<String>()
            val suppressed = linkedSetOf<String>()
            for (member in members) {
                if (member.link != null) continue
                if (collectionMemberBlocked(member, blocked)) { suppressed += member.id; continue }
                val entry = entries.optJSONObject(member.id)
                if (entry == null || entry.optString("identity") != member.identityJson) { pending += member.id; continue }
                val row = CollectionMemberMetadata.display(member.copy(metadataJson = entry.getString("metadata")), blocked, ::fullAccess)
                val appId = entry.optString("app").takeIf { it.isNotBlank() && it != "null" }
                val live = appId?.let(history::get)
                val aliases = entry.getJSONArray("aliases").let { a -> (0 until a.length()).mapTo(linkedSetOf()) { a.getString(it) } }
                val candidate = when {
                    row == null -> null
                    appId != null -> live?.takeIf { !it.hidden && !it.isPrivate && it.state == DownloadState.COMPLETED &&
                        it.savedUri == row.savedUri && it.sizeBytes == row.sizeBytes && it.createdAtEpochMillis == row.createdAtEpochMillis &&
                        it.mediaType == row.mediaType }?.let { LibraryMediaRef(it.id, aliases, it, it.id) }
                    externalCurrent && fullAccess(row.mediaType) -> LibraryMediaRef(row.id, aliases, row.copy(
                        previewUrl = row.savedUri.takeIf { row.mediaType != MediaType.AUDIO },
                        sourceAudioTitle = entry.optString("title").takeIf { it.isNotBlank() && it != "null" },
                        sourceAudioArtist = entry.optString("artist").takeIf { it.isNotBlank() && it != "null" }), null)
                    else -> null
                }
                if (candidate == null || collectionRefBlocked(candidate, blocked) ||
                    !CollectionAddressResolver.matches(MediaOrganizationPolicy.decode(member.identityJson), candidate)) {
                    pending += member.id; continue
                }
                resolved[member.id] = candidate
            }
            if (guard(blocked) != expectedGuard || (resolved.isEmpty() && pending.isNotEmpty())) null else AddressedCollection(resolved.values.distinctBy { it.key },
                pending, emptyMap(), suppressed, members.associate { it.id to it.identityJson }, resolved)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }

    suspend fun write(id: String, members: List<MediaCollectionMemberEntity>, value: AddressedCollection,
        blocked: Set<String>, before: PhoneStoreStamp, expectedGuard: String = guard(blocked)) = withContext(Dispatchers.IO) {
        if (guard(blocked) != expectedGuard) return@withContext
        val after = phone.storeStamp()
        val externalCurrent = before.versions != null && before.generation != null && before == after
        // Preserve only an already persisted, exact same entry on an inconclusive
        // refresh; do not mint a fresh verification from retained screen data.
        val old = cache.read(id, expectedGuard)
        val oldEntries = old?.optJSONObject("members")
        val oldExternalCurrent = externalCurrent && old?.optString("versions") == after.versions && old?.optString("generation") == after.generation
        val entries = JSONObject()
        for (member in members) {
            val ref = value.resolved[member.id] ?: continue
            if (member.identityJson == null || value.checkedMembers[member.id] != member.identityJson ||
                member.id in value.invalidated || member.id in value.suppressed ||
                collectionMemberBlocked(member, blocked) || collectionRefBlocked(ref, blocked) ||
                !CollectionAddressResolver.matches(MediaOrganizationPolicy.decode(member.identityJson), ref)) continue
            if (ref.appDownloadId == null && (!externalCurrent || !fullAccess(ref.item.mediaType))) continue
            val metadata = CollectionMemberMetadata.encode(ref)
            if (member.id in value.pending) {
                val prior = oldEntries?.optJSONObject(member.id)
                if (prior != null && prior.optString("identity") == member.identityJson && prior.optString("metadata") == metadata &&
                    (ref.appDownloadId != null || oldExternalCurrent)) entries.put(member.id, prior)
                continue
            }
            entries.put(member.id, JSONObject().put("identity", member.identityJson)
                .put("metadata", metadata).put("app", ref.appDownloadId)
                .put("aliases", JSONArray(ref.aliases.sorted())).put("title",ref.item.sourceAudioTitle).put("artist",ref.item.sourceAudioArtist))
        }
        if (guard(blocked) == expectedGuard)
            cache.write(id, expectedGuard, JSONObject().put("members", entries).put("versions", after.versions).put("generation", after.generation))
    }

    suspend fun stamp() = phone.storeStamp()
}

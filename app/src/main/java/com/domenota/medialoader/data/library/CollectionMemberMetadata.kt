package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.database.MediaCollectionMemberEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.playback.PublicMediaIdentity
import org.json.JSONObject

/** Minimal display-only snapshot of the exact member. No remote URL, credentials,
 * recovery selector or media bytes. Never a candidate for playback/recovery. */
internal object CollectionMemberMetadata {
    fun encode(ref: LibraryMediaRef): String {
        val row = ref.item
        require(!row.isPrivate && !row.hidden && row.state == DownloadState.COMPLETED && !row.savedUri.isNullOrBlank())
        return JSONObject().put("id", ref.key).put("app", ref.appDownloadId)
            .put("identity", MediaOrganizationPolicy.encode(MediaOrganizationPolicy.keys(ref)))
            .put("name", row.originalName).put("type", row.mediaType.name).put("provider", row.providerId)
            .put("uri", row.savedUri).put("date", row.createdAtEpochMillis).put("size", row.sizeBytes)
            .put("duration", row.durationMs).toString()
    }

    fun display(member: MediaCollectionMemberEntity, blocked: Set<String>, canReadDevice: (MediaType) -> Boolean): DownloadEntity? {
        if (collectionMemberBlocked(member, blocked)) return null
        val json = member.metadataJson ?: return null
        return runCatching {
            val value = JSONObject(json)
            val type = MediaType.valueOf(value.getString("type"))
            val appId = if (value.isNull("app")) null else value.getString("app")
            val id = value.getString("id")
            val uri = value.getString("uri")
            val keys = MediaOrganizationPolicy.decode(member.identityJson)
            // Only display metadata attached to the persisted identity, and never
            // publish an old external thumbnail after its permission was revoked.
            if (id in blocked || appId?.let(blocked::contains)==true || PublicMediaIdentity.uriKeys(uri).any(blocked::contains)) return null
            val evidence = MediaOrganizationPolicy.decode(value.getString("identity"))
            if (evidence.none(keys::contains)) return null
            val appBound = appId != null && "app:$appId" in keys
            if (!appBound && !canReadDevice(type)) return null
            DownloadEntity(id, value.getString("provider"), value.getString("name"), type,
                DownloadState.COMPLETED, uri, if (value.isNull("size")) null else value.getLong("size"), value.getLong("date"),
                durationMs = if (value.isNull("duration")) null else value.getLong("duration"))
        }.getOrNull()
    }
}

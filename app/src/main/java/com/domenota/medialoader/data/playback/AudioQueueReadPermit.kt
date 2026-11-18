package com.domenota.medialoader.data.playback

import androidx.media3.common.MediaItem
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.catalog.visibility.FolderVisibilityLibraryGuard
import com.domenota.medialoader.data.library.LibraryMediaRef

/** Only a live known-folder read can opt out of the global mutation hold.
 * The public model is authenticated separately; it is not a URI write permit. */
internal data class FolderAudioReadAccess(
    val visibility: FolderVisibilityLibraryGuard?, val privacy: Set<String>?,
    val privacyEpoch: Long, val musicEpoch: Long, val permissionSignature: List<Boolean>,
    val recoveryVersion: Long, val publicRefs: List<LibraryMediaRef>,
)
internal class AudioQueueReadPermit private constructor(
    private val initial: FolderAudioReadAccess,
    private val current: () -> FolderAudioReadAccess,
) {
    fun requireCurrent(items: List<MediaItem>) {
        val live = current()
        check(sameAccess(initial, live) && allows(live, items)) { "Треки или доступ изменились" }
    }
    fun requireRefsCurrent(refs: List<LibraryMediaRef>) {
        val live=current()
        check(sameAccess(initial,live) && allowsRefs(live,refs)) { "Треки или доступ изменились" }
    }
    companion object {
        fun acquire(access: FolderAudioReadAccess, refs: List<LibraryMediaRef>,
            current: () -> FolderAudioReadAccess): AudioQueueReadPermit {
            check(allowsRefs(access, refs)) { "Треки недоступны во время переноса" }
            return AudioQueueReadPermit(access, current)
        }
        private fun sameAccess(a: FolderAudioReadAccess, b: FolderAudioReadAccess): Boolean =
            a.privacy == b.privacy && a.privacyEpoch == b.privacyEpoch && a.musicEpoch == b.musicEpoch &&
                a.permissionSignature == b.permissionSignature && a.recoveryVersion == b.recoveryVersion &&
                a.visibility?.policy?.pendingFolder == b.visibility?.policy?.pendingFolder

        private fun allowsRefs(access: FolderAudioReadAccess, refs: List<LibraryMediaRef>): Boolean {
            val guard=access.visibility ?: return false
            val privacy=access.privacy ?: return false
            if(guard.policy.unknownPending || guard.policy.pendingFolder==null || refs.isEmpty())return false
            val blocked=guard.blockedKeys()+privacy
            val byKey=access.publicRefs.associateBy { it.key }
            return refs.all { candidate ->
                val ref=byKey[candidate.key] ?: return@all false
                candidate.item.savedUri==ref.item.savedUri && !candidate.item.isPrivate && !candidate.item.hidden &&
                    candidate.item.mediaType==MediaType.AUDIO && candidate.item.state==DownloadState.COMPLETED &&
                    !ref.item.isPrivate && !ref.item.hidden && ref.item.mediaType==MediaType.AUDIO &&
                    (ref.appDownloadId!=null || access.permissionSignature.any { it }) && !guard.excluded(ref) &&
                    ref.key !in blocked && ref.aliases.none(blocked::contains) && candidate.aliases.none(blocked::contains) &&
                    ref.aliases.any { it.startsWith("file-stat:") && it in candidate.aliases }
            }
        }
        private fun allows(access: FolderAudioReadAccess, items: List<MediaItem>): Boolean {
            val guard = access.visibility ?: return false
            val privacy = access.privacy ?: return false
            if (guard.policy.unknownPending || guard.policy.pendingFolder == null || items.isEmpty()) return false
            val blocked = guard.blockedKeys() + privacy
            val allowed = access.publicRefs.associateBy { it.key }
            return items.all { item ->
                val ref = allowed[item.mediaId] ?: return@all false
                val uri = item.mediaMetadata.extras?.getString(AudioPlaybackMetadata.PUBLIC_URI)
                    ?: item.localConfiguration?.uri?.toString()
                val keys = item.mediaMetadata.extras?.getStringArrayList(PublicMediaIdentity.EXTRA_KEYS).orEmpty()
                !PrivatePlaybackPolicy.isPrivate(item) && !ref.item.isPrivate && !ref.item.hidden &&
                    ref.item.mediaType == MediaType.AUDIO && ref.item.state == DownloadState.COMPLETED &&
                    uri != null && uri == ref.item.savedUri &&
                    (ref.appDownloadId != null || access.permissionSignature.any { it }) &&
                    !guard.excluded(ref) && ref.key !in blocked && ref.aliases.none(blocked::contains) &&
                    keys.none(blocked::contains) &&
                    ref.aliases.any { it.startsWith("file-stat:") && it in keys }
            }
        }
    }
}

internal fun requireAudioQueueRead(items: List<MediaItem>, permit: AudioQueueReadPermit?) {
    if (permit == null) com.domenota.medialoader.data.storage.HistoricalRecoverySignals.requireIdle()
    else permit.requireCurrent(items)
}

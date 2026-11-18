package com.domenota.medialoader.data.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.domenota.medialoader.data.catalog.visibility.VisibilityMoveRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object FolderVisibilityPlaybackSafety {
    fun keys(item: MediaItem): Set<String>? {
        if (PrivatePlaybackPolicy.isPrivate(item)) return null
        val uri = item.mediaMetadata.extras?.getString(AudioPlaybackMetadata.PUBLIC_URI)
            ?: item.localConfiguration?.uri?.toString() ?: return null
        val identity = item.mediaMetadata.extras?.getStringArrayList(PublicMediaIdentity.EXTRA_KEYS).orEmpty()
        // A bare URI can alias an affected SAF/file locator. Preserve only a
        // queue entry with a previously authenticated physical identity.
        if (identity.none { it.startsWith("file-stat:") }) return null
        return identity.toSet() + PublicMediaIdentity.uriKeys(uri) + item.mediaId + "app:${item.mediaId}"
    }
    /** Check actual READ at the final Main boundary; raw stat access (including
     * all-files access) never authorizes preserving a phone-media queue. */
    fun currentPrivacyWithRead(context: Context, type: com.domenota.medialoader.core.model.MediaType,
        privateIds: Set<String>?): Set<String>? = privateIds?.takeIf {
        com.domenota.medialoader.data.library.PhoneMediaLibrary.canReadMissingMetadata(type, android.os.Build.VERSION.SDK_INT) { permission ->
            androidx.core.content.ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /** Must be captured on the actual player's looper, before doing any IO. */
    fun snapshot(player: Player): List<MediaItem> = (0 until player.mediaItemCount).map { index ->
        val item = player.getMediaItemAt(index)
        val extras = android.os.Bundle(item.mediaMetadata.extras ?: android.os.Bundle())
        extras.getStringArrayList(PublicMediaIdentity.EXTRA_KEYS)?.let {
            extras.putStringArrayList(PublicMediaIdentity.EXTRA_KEYS, ArrayList(it))
        }
        item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
    }

    /** Metadata-only path stats are sufficient when the queue already authenticated a path.
     * Never accept an old inode assertion after a path replacement (including a hard link). */
    suspend fun prove(context: Context, items: List<MediaItem>): List<Set<String>?> =
        withContext(Dispatchers.IO) {
            prove(items, PublicMediaIdentity::pathKeys) { PublicMediaIdentity.capture(context, it) }
        }

    internal suspend fun prove(items: List<MediaItem>, stat: (String) -> Set<String>,
        capture: suspend (String) -> Set<String>): List<Set<String>?> = items.map { item ->
        val authenticated = keys(item) ?: return@map null
        val expected = authenticated.filter { it.startsWith("file-stat:") }.toSet()
        val paths = authenticated.filter { it.startsWith("file:") }
        val fresh = if (paths.isNotEmpty()) {
            val proofs = paths.map { stat(it.removePrefix("file:")) }
            if (proofs.any { proof -> proof.filter { it.startsWith("file-stat:") }.toSet() != expected })
                return@map null
            proofs.flatten().toSet()
        } else {
            val uri = item.mediaMetadata.extras?.getString(AudioPlaybackMetadata.PUBLIC_URI)
                ?: item.localConfiguration?.uri?.toString() ?: return@map null
            capture(uri).also { proof ->
                if (proof.filter { it.startsWith("file-stat:") }.toSet() != expected) return@map null
            }
        }
        authenticated + fresh
    }

    private fun clear(player: Player) {
        player.pause(); player.stop(); player.clearMediaItems()
        check(player.mediaItemCount == 0)
    }

    /** Apply only a proof for the exact queue; recheck current privacy on Main as well.
     * A timeline race is unknown, so clear conservatively rather than apply stale indices. */
    fun removeAffected(player: Player, affected: Set<String>, items: List<MediaItem>,
        identities: List<Set<String>?>, privateIds: Set<String>?) {
        if (privateIds == null || items.size != player.mediaItemCount || identities.size != items.size ||
            items.indices.any { !AudioQueuePlayback.sameQueueItem(items[it], player.getMediaItemAt(it)) } ||
            identities.any { it == null } || items.any { PrivatePlaybackPolicy.isPrivate(it) } ||
            identities.any { it!!.any(privateIds::contains) }) {
            clear(player)
            return
        }
        val remove = identities.indices.filter { identities[it]!!.any(affected::contains) }
        val current = player.currentMediaItem
        val currentAffected = player.currentMediaItemIndex in remove
        if (currentAffected) { player.pause(); player.stop() }
        for (index in remove.asReversed()) player.removeMediaItem(index)
        check((0 until player.mediaItemCount).all { index ->
            keys(player.getMediaItemAt(index))?.none(affected::contains) == true
        }) { "В очереди осталась ссылка переносимого файла" }
        if (!currentAffected && current != null) check(player.currentMediaItem?.let {
            AudioQueuePlayback.sameQueueItem(current, it)
        } == true) { "Не удалось сохранить воспроизведение другого файла" }
    }

}

/** Exact batch preflight. O(batch members) physical lookup, never a library scan.
 * The existing blanket historical-date recovery helper remains unchanged. */
internal suspend fun ensureFolderVisibilityPlaybackSafe(context: Context, members: List<VisibilityMoveRecord>) {
    check(com.domenota.medialoader.data.storage.HistoricalRecoverySignals.isRunning.value)
    if (members.isEmpty()) return
    val affected = withContext(Dispatchers.IO) {
        buildSet {
            for (member in members) {
                add(member.downloadId); add("app:${member.downloadId}")
                member.publicOrigin?.physicalPath?.let { path ->
                    addAll(PublicMediaIdentity.uriKeys(android.net.Uri.fromFile(java.io.File(path)).toString()))
                    addAll(PublicMediaIdentity.pathKeys(path))
                }
                for (uri in listOfNotNull(member.sourceUri, member.logicalSourceUri,
                    member.destinationUri, member.publicOrigin?.uri).distinct()) {
                    add(uri); addAll(PublicMediaIdentity.uriKeys(uri))
                    addAll(PublicMediaIdentity.capture(context, uri))
                }
            }
        }
    }
    AudioPlaybackService.excludeFolderVisibilityMembers(context, affected)
    FloatingVideoService.excludeFolderVisibilityMembers(context, affected)
}

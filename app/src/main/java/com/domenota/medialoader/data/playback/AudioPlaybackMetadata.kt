package com.domenota.medialoader.data.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*

internal object AudioPlaybackMetadata {
    const val PUBLIC_URI = "public_saved_uri"
    const val PLAYLIST_ID = "origin_playlist_id"
    fun publicRow(item: MediaItem, lookup: List<DownloadEntity>, blocked: Set<String> = emptySet()): DownloadEntity? {
        val meta = item.mediaMetadata
        val identities = meta.extras?.getStringArrayList(PublicMediaIdentity.EXTRA_KEYS).orEmpty()
        val uri = meta.extras?.getString(PUBLIC_URI) ?: item.localConfiguration?.uri?.toString()
        if (meta.extras?.getBoolean("private_media") == true || item.mediaId in blocked || identities.any(blocked::contains) ||
            (uri != null && PublicMediaIdentity.uriKeys(uri).any(blocked::contains))) return null
        lookup.firstOrNull { !it.isPrivate && !it.hidden && (it.id == item.mediaId || (uri != null && it.savedUri == uri)) }?.let { return it }
        return DownloadEntity(item.mediaId, "device", meta.title?.toString() ?: "Трек", MediaType.AUDIO,
            DownloadState.COMPLETED, uri, null, 0, sourceAudioTitle = meta.title?.toString(),
            sourceAudioArtist = meta.artist?.toString(), sourceAudioArtworkUrl = meta.artworkUri?.toString())
    }
    /** A service session can outlive the activity and its phone-media index. Preserve raw
     * session IDs so a mini-player click resolves even before MediaStore is indexed. */
    fun publicSessionRows(items: List<MediaItem>, lookup: List<DownloadEntity>, blocked: Set<String>): List<DownloadEntity> {
        if (items.isEmpty()) return emptyList()
        val byId = HashMap<String, DownloadEntity>()
        val byUri = HashMap<String, DownloadEntity>()
        val ordinal = java.util.IdentityHashMap<DownloadEntity, Int>()
        lookup.forEachIndexed { index, row -> ordinal.putIfAbsent(row, index); if (!row.isPrivate && !row.hidden) {
            byId.putIfAbsent(row.id, row)
            row.savedUri?.let { byUri.putIfAbsent(it, row) }
        } }
        return items.mapNotNull { media ->
            val uri = media.mediaMetadata.extras?.getString(PUBLIC_URI)
                ?: media.localConfiguration?.uri?.toString()
            // Preserve the original first-in-list resolution when ID and URI disagree.
            val candidates = listOfNotNull(byId[media.mediaId], uri?.let(byUri::get))
                .sortedBy { ordinal[it] ?: Int.MAX_VALUE }
            publicRow(media, candidates, blocked)?.takeIf { !it.savedUri.isNullOrBlank() }
                ?.copy(id = media.mediaId)
        }.distinctBy { it.id }
    }

    fun matchesPublicUri(item: MediaItem, uri: String): Boolean {
        if (item.mediaMetadata.extras?.getBoolean("private_media") == true) return false
        val saved = item.mediaMetadata.extras?.getString(PUBLIC_URI) ?: item.localConfiguration?.uri?.toString()
            ?: return false
        val keys = PublicMediaIdentity.uriKeys(uri)
        return PublicMediaIdentity.uriKeys(saved).any(keys::contains)
    }

    fun revisedItem(item: MediaItem, row: DownloadEntity): MediaItem {
        if (row.isPrivate || row.hidden || row.savedUri == null || !matchesPublicUri(item, row.savedUri)) return item
        return item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon()
            .setTitle(row.recognizedTitle ?: row.sourceAudioTitle ?: row.originalName)
            .setArtist(row.recognizedArtist ?: row.sourceAudioArtist)
            .setAlbumTitle(row.recognizedAlbum).build()).build()
    }

    fun item(row: DownloadEntity, identities: Set<String>, playlistId: String? = null): MediaItem = MediaItem.Builder()
        .setMediaId(row.id).setUri(requireNotNull(row.savedUri))
        .setMediaMetadata(MediaMetadata.Builder()
            .setTitle(row.recognizedTitle ?: row.sourceAudioTitle ?: row.originalName)
            .setArtist(row.recognizedArtist ?: row.sourceAudioArtist)
            .setArtworkUri((row.recognitionArtworkUrl ?: row.sourceAudioArtworkUrl ?: row.previewUrl)
                ?.takeIf { !row.isPrivate }?.let(Uri::parse))
            .setExtras(Bundle().apply {
                putBoolean("private_media", row.isPrivate)
                if (!row.isPrivate && playlistId != null) putString(PLAYLIST_ID,playlistId)
                putStringArrayList(PublicMediaIdentity.EXTRA_KEYS, ArrayList(identities))
                if (!row.isPrivate) putString(PUBLIC_URI, row.savedUri)
            }).build()).build()
}

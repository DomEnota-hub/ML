package com.domenota.medialoader.data.playback

import android.content.Context
import androidx.media3.common.MediaItem
import java.util.Base64

/** Shared visibility gate for listening history and resumable queues.
 * Decode hidden records once per preference generation. Queue workers receive a
 * frozen gate, never a closure that reads mutable service/player state. */
internal class PublicListeningIdentity(context: Context,
    private val accessSnapshot: (() -> Access)? = null,
    private val allowed: (MediaItem) -> Boolean) {
    internal data class Access(val ready: Boolean, val blocked: Set<String>) {
        fun allows(item: MediaItem): Boolean {
            if (!ready || PrivatePlaybackPolicy.isPrivate(item) || item.mediaId in blocked) return false
            val extras = item.mediaMetadata.extras ?: return false
            val uri = extras.getString(AudioPlaybackMetadata.PUBLIC_URI) ?: return false
            return blocked.isEmpty() || (extras.getStringArrayList(PublicMediaIdentity.EXTRA_KEYS).orEmpty().none(blocked::contains) &&
                PublicMediaIdentity.uriKeys(uri).none(blocked::contains))
        }
    }
    internal data class Token(val hiddenRecords: Set<String>, val access: Access?)
    internal class Snapshot(val token: Token, private val ids: Set<String>, private val uris: Set<String>,
        private val fallback: (MediaItem) -> Boolean) {
        fun keys(item: MediaItem?): Set<String> {
            if (item == null || !(token.access?.allows(item) ?: fallback(item)) || PrivatePlaybackPolicy.isPrivate(item)) return emptySet()
            val extras = item.mediaMetadata.extras ?: return emptySet()
            val uri = extras.getString(AudioPlaybackMetadata.PUBLIC_URI) ?: return emptySet()
            if (item.mediaId in ids || uri in uris) return emptySet()
            if (ids.isNotEmpty() || uris.isNotEmpty()) {
                for (alias in extras.getStringArrayList(PublicMediaIdentity.EXTRA_KEYS).orEmpty()) {
                    if (alias.startsWith("uri:")) { if (alias.removePrefix("uri:") in uris) return emptySet() }
                    else if (!alias.startsWith("file:") && !alias.startsWith("media:") && !alias.startsWith("file-stat:") && alias in ids) return emptySet()
                }
            }
            return extras.getStringArrayList(AudioPlaybackMetadata.LISTENING_KEYS).orEmpty().toSet()
        }
    }
    private val prefs = context.applicationContext.getSharedPreferences("music_folder_visibility", Context.MODE_PRIVATE)
    private var lastHiddenKeys: Set<String>? = null
    private var hiddenIds = emptySet<String>()
    private var hiddenUris = emptySet<String>()
    private fun updateHidden(): Set<String> {
        val keys = prefs.getStringSet("hidden_tracks", emptySet()).orEmpty()
        if (keys == lastHiddenKeys) return lastHiddenKeys.orEmpty()
        // Reject noncanonical encodings just as the old exact encoded comparison did.
        fun decode(value: String): String? = runCatching {
            val bytes = Base64.getUrlDecoder().decode(value)
            String(bytes, Charsets.UTF_8).takeIf { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8)) == value }
        }.getOrNull()
        val ids = mutableSetOf<String>(); val uris = mutableSetOf<String>()
        keys.forEach { record ->
            val dot = record.indexOf('.')
            if (dot >= 0) {
                decode(record.substring(0, dot))?.let(ids::add)
                decode(record.substring(dot + 1))?.let(uris::add)
            }
        }
        lastHiddenKeys = keys.toSet(); hiddenIds = ids; hiddenUris = uris
        return lastHiddenKeys.orEmpty()
    }
    fun visibilityToken(): Token = Token(updateHidden(), accessSnapshot?.invoke())
    fun snapshot(): Snapshot = Snapshot(visibilityToken(), hiddenIds, hiddenUris, allowed)
    fun keys(item: MediaItem?): Set<String> = snapshot().keys(item)
    fun keysForQueue(items: List<MediaItem>): List<Set<String>> = snapshot().let { gate -> items.map(gate::keys) }
}

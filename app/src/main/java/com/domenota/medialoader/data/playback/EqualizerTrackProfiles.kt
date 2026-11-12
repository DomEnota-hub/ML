package com.domenota.medialoader.data.playback

import androidx.media3.common.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Identity is independent of mutable display names and playback queue position.
 * Never collect private vault track identities or artwork in this public preference.
 */
internal data class EqualizerTrack(val keys: Set<String>, val title: String) {
    init { require(keys.isNotEmpty()) }
    companion object {
        fun fromMediaItem(item: MediaItem?): EqualizerTrack? {
            if (item == null || item.mediaMetadata.extras?.getBoolean("private_media") == true) return null
            val extras = item.mediaMetadata.extras
            val uri = extras?.getString(AudioPlaybackMetadata.PUBLIC_URI)
                ?: item.localConfiguration?.uri?.toString()
            if (uri.isNullOrBlank()) return null
            val keys = PublicMediaIdentity.uriKeys(uri).toMutableSet()
            if (item.mediaId.isNotBlank() && item.mediaId != MediaItem.DEFAULT_MEDIA_ID) {
                keys += "id:${item.mediaId}"
            }
            // The library has already established these physical-file aliases.
            // Do not open a provider or rescan the library on the playback thread.
            extras?.getStringArrayList(PublicMediaIdentity.EXTRA_KEYS).orEmpty()
                .filter { it.startsWith("media:") || it.startsWith("uri:") ||
                    it.startsWith("file-stat:") || it.startsWith("file:") }
                .forEach { keys += it }
            return EqualizerTrack(keys.toSet(), item.mediaMetadata.title?.toString()?.takeIf(String::isNotBlank) ?: "Трек")
        }
    }
}

internal data class EqualizerTrackProfile(val keys: Set<String>, val settings: EqualizerSettings)

/** Global preferences are the fallback, never the settings of the previous track. */
internal data class EqualizerProfileState(
    val global: EqualizerSettings = EqualizerSettings(),
    val tracks: List<EqualizerTrackProfile> = emptyList(),
) {
    private fun matches(track: EqualizerTrack) = tracks.filter { profile ->
        profile.keys.any(track.keys::contains)
    }
    fun profile(track: EqualizerTrack?): EqualizerTrackProfile? =
        track?.let { matches(it).singleOrNull() }
    fun effective(track: EqualizerTrack?): EqualizerSettings = profile(track)?.settings ?: global

    /** Null settings means restore inheritance for an existing track, not reset other tracks. */
    fun changed(track: EqualizerTrack?, settings: EqualizerSettings?): EqualizerProfileState {
        val safe = settings?.copy(gainsDb = settings.gainsDb.toList())
        if (track == null) return copy(global = requireNotNull(safe))
        val existing = matches(track)
        require(existing.size <= 1) { "Неоднозначный идентификатор трека" }
        val retained = tracks - existing.toSet()
        return copy(tracks = if (safe == null) retained else retained + EqualizerTrackProfile(
            (track.keys + existing.singleOrNull()?.keys.orEmpty()).toSet(), safe))
    }
}

/** Published by the service itself, including background, shuffle and queue transitions.
 * The UI observes this rather than guessing which screen or list item is playing.
 */
internal object EqualizerPlaybackTrack {
    private val mutable = MutableStateFlow<EqualizerTrack?>(null)
    val track = mutable.asStateFlow()
    fun update(value: EqualizerTrack?) { mutable.value = value }
}

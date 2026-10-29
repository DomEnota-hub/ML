package com.domenota.medialoader.data.search

/** Keeps a source switch from affecting a transfer that has already started. */
object MusicSourcePolicy {
    fun queuedProviderToCancel(
        previous: FullSongSource,
        current: FullSongSource,
    ): String? = previous.providerId.takeIf {
        previous != current && previous != FullSongSource.AUTO && current != FullSongSource.AUTO
    }
}

package com.domenota.medialoader.data.library

/** The encoded digest and whole-file digest are independent of acoustic scan mode.
 * A cache from another mode is metadata only; never reuse sampled/full acoustic
 * evidence interchangeably. Unknown/changed identity or missing digest fails closed.
 */
internal object MusicMetadataCheckpointPolicy {
    fun forOtherMode(
        track: DuplicateTrack, uri: String, bytes: Long, modified: Long?,
        folder: String?, epoch: Long, filename: String,
    ): DuplicateTrack? {
        if (modified == null || bytes <= 0 || track.uri != uri ||
            track.bytes != bytes || track.modified != modified || track.folder != folder ||
            track.privacyEpoch != epoch || track.filename != filename ||
            track.sha.isBlank() || track.payloadSha.isNullOrBlank()
        ) return null
        return track.copy(acoustic = null)
    }
}

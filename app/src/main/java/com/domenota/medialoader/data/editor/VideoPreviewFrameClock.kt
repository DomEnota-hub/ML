package com.domenota.medialoader.data.editor

/** Decode can prefetch another period. Map EACH queued graph frame to its own source-period clock,
 * rather than reading the player's current item or the latest metadata callback on the GL thread.
 * Retains enough entries for buffered input plus Media3's two replay frames, bounded at256.
 */
internal class VideoPreviewFrameClock {
    private val frames = LinkedHashMap<Long, Long>()
    @Synchronized fun record(graphUs: Long, sourceUs: Long) {
        frames[graphUs] = sourceUs
        while (frames.size > 256) frames.remove(frames.keys.first())
    }
    @Synchronized fun sourceTimeUs(graphUs: Long): Long = frames[graphUs] ?: graphUs
}

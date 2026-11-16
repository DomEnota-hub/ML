package com.domenota.medialoader.ui

/** First load remains progressive. Once a complete generation has been published,
 * rebuilding identities publishes only its complete replacement. Recovery can still
 * clear the flow to null; this policy retains no media, aliases, or access decisions. */
internal class AppReferencePublicationPolicy {
    private var completed = false
    fun shouldPublish(resolved: Int, total: Int, firstBatch: Boolean, elapsedSincePublishMs: Long): Boolean {
        require(resolved in 0..total)
        return resolved == total || (!completed && (firstBatch || elapsedSincePublishMs >= 250L))
    }
    /** Call only after the complete emission succeeds, never when a build is cancelled. */
    fun completed() { completed = true }
}

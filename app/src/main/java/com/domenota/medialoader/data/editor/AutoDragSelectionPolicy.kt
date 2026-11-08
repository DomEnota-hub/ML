package com.domenota.medialoader.data.editor

/**
 * A MobileSAM point prompt is valid only after a deliberate drag, not a tap.
 * The last source-space point is used, so the finger's release position is the target.
 * Multi-touch cancellation is handled by the gesture owner before this policy is called.
 */
internal object AutoDragSelectionPolicy {
    fun releasePrompt(
        points: List<MaskPoint>,
        maxTravelPx: Float,
        minDragPx: Float,
    ): MaskPoint? {
        require(minDragPx > 0f && minDragPx.isFinite())
        if (!maxTravelPx.isFinite() || maxTravelPx <= minDragPx || points.size < 2) return null
        return points.last()
    }
}

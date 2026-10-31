package com.domenota.medialoader.ui.screens

/**
 * Reordering model for the video clip strip. Uses the exact widths that the
 * timeline renders, so a long drag moves through every crossed clip rather
 * than moving only one item regardless of drag distance.
 */
internal object VideoClipReorderMath {
    fun widthDp(durationMs: Long, zoom: Float): Float =
        (durationMs / 1000f * 15f * zoom).coerceIn(76f, 380f)

    fun destinationIndex(
        durationsMs: List<Long>,
        fromIndex: Int,
        dragPx: Float,
        zoom: Float,
        density: Float,
        spacingDp: Float = 3f,
    ): Int {
        require(fromIndex in durationsMs.indices)
        require(density > 0f && zoom > 0f)
        if (!dragPx.isFinite() || dragPx == 0f) return fromIndex
        var destination = fromIndex
        var traversedPx = 0f
        if (dragPx > 0f) {
            while (destination < durationsMs.lastIndex) {
                val crossing = (widthDp(durationsMs[destination], zoom) +
                    widthDp(durationsMs[destination + 1], zoom)) / 2f + spacingDp
                if (dragPx < traversedPx + crossing * density) break
                traversedPx += crossing * density
                destination++
            }
        } else {
            while (destination > 0) {
                val crossing = (widthDp(durationsMs[destination], zoom) +
                    widthDp(durationsMs[destination - 1], zoom)) / 2f + spacingDp
                if (-dragPx < traversedPx + crossing * density) break
                traversedPx += crossing * density
                destination--
            }
        }
        return destination
    }
}

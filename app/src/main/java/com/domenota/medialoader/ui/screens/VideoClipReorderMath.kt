package com.domenota.medialoader.ui.screens

import kotlin.math.roundToLong

/**
 * A shared time scale governs thumbnail width, touch trim and drag reordering.
 *
 * Historical 380 dp max width made all clips longer than ~10 seconds appear
 * identical in width. That is why trimming 20s -> 15s had no visible effect.
 * Fit the *untrimmed* longest clip into at most 960 dp, preserving the scale
 * while either trim handle is being dragged.
 */
internal object VideoClipReorderMath {
    fun pixelsPerSecondDp(zoom: Float, referenceDurationMs: Long): Float {
        require(zoom.isFinite() && zoom > 0f && referenceDurationMs > 0L)
        return minOf(15f * zoom, 960_000f / referenceDurationMs)
            .coerceAtLeast(0.05f)
    }

    fun widthDp(durationMs: Long, zoom: Float, referenceDurationMs: Long = durationMs): Float =
        (durationMs.coerceAtLeast(1L) / 1000f *
            pixelsPerSecondDp(zoom, referenceDurationMs)).coerceAtLeast(56f)

    /** The live yellow range sits over the ORIGINAL source reel during trimming. */
    data class TrimSelection(val leftDp: Float, val rightDp: Float) {
        val keptDp: Float get() = (rightDp - leftDp).coerceAtLeast(0f)
    }

    fun trimSelection(sourceDurationMs: Long, inMs: Long, outMs: Long,
        displayWidthDp: Float): TrimSelection {
        require(sourceDurationMs > 0L && inMs >= 0L && inMs < outMs &&
            outMs <= sourceDurationMs)
        require(displayWidthDp.isFinite() && displayWidthDp > 0f)
        return TrimSelection(
            leftDp = inMs.toDouble().div(sourceDurationMs).times(displayWidthDp)
                .toFloat().coerceIn(0f, displayWidthDp),
            rightDp = outMs.toDouble().div(sourceDurationMs).times(displayWidthDp)
                .toFloat().coerceIn(0f, displayWidthDp),
        )
    }

    /** Keep 17dp touch handles apart even for a selected interval of 0.1s. */
    data class TrimGrips(val leftDp: Float, val rightDp: Float)

    fun trimGrips(selection: TrimSelection, fullWidthDp: Float,
        handleWidthDp: Float = 17f): TrimGrips {
        require(fullWidthDp.isFinite() && handleWidthDp.isFinite() &&
            handleWidthDp > 0f && fullWidthDp >= handleWidthDp * 2f)
        val left = (selection.leftDp - handleWidthDp)
            .coerceIn(0f, fullWidthDp - 2f * handleWidthDp)
        val right = selection.rightDp.coerceIn(left + handleWidthDp,
            fullWidthDp - handleWidthDp)
        return TrimGrips(left, right)
    }

    /** +/- screen pixels to +/- actual trimmed SOURCE milliseconds. */
    fun sourceDeltaMs(
        cumulativeDragPx: Float,
        pixelsPerSecondDp: Float,
        density: Float,
        speed: Float,
    ): Long {
        require(pixelsPerSecondDp > 0f && density > 0f && speed > 0f)
        if (!cumulativeDragPx.isFinite()) return 0L
        return (cumulativeDragPx / (pixelsPerSecondDp * density) * 1000.0 * speed)
            .roundToLong()
    }

    fun destinationIndex(
        durationsMs: List<Long>,
        fromIndex: Int,
        dragPx: Float,
        zoom: Float,
        density: Float,
        spacingDp: Float = 3f,
        referenceDurationMs: Long = durationsMs.maxOrNull() ?: 1L,
    ): Int {
        require(fromIndex in durationsMs.indices)
        require(density > 0f && zoom > 0f)
        if (!dragPx.isFinite() || dragPx == 0f) return fromIndex
        var destination = fromIndex
        var traversedPx = 0f
        if (dragPx > 0f) {
            while (destination < durationsMs.lastIndex) {
                val crossing = (widthDp(durationsMs[destination], zoom, referenceDurationMs) +
                    widthDp(durationsMs[destination + 1], zoom, referenceDurationMs)) / 2f + spacingDp
                if (dragPx < traversedPx + crossing * density) break
                traversedPx += crossing * density
                destination++
            }
        } else {
            while (destination > 0) {
                val crossing = (widthDp(durationsMs[destination], zoom, referenceDurationMs) +
                    widthDp(durationsMs[destination - 1], zoom, referenceDurationMs)) / 2f + spacingDp
                if (-dragPx < traversedPx + crossing * density) break
                traversedPx += crossing * density
                destination--
            }
        }
        return destination
    }
}

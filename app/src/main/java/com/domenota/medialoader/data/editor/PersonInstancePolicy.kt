package com.domenota.medialoader.data.editor

import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/** A COCO person bounding box in SOURCE-normalized coordinates. */
internal data class PersonBox(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
) {
    val width get() = right - left
    val height get() = bottom - top
    val center get() = MaskPoint((left + right) / 2f, (top + bottom) / 2f)

    fun valid(): Boolean =
        listOf(left, top, right, bottom).all { it.isFinite() } &&
        left >= 0f && top >= 0f && right <= 1f && bottom <= 1f &&
        width > .01f && height > .01f

    fun contains(p: MaskPoint) = p.x in left..right && p.y in top..bottom
}

internal data class PersonDetection(
    val id: Int,
    val box: PersonBox,
    val score: Float,
)

internal data class PersonInstance(
    val detection: PersonDetection,
    val strokes: List<MaskStroke>,
)

/**
 * Person detector supplies separate boxes; MobileSAM supplies detailed masks.
 * Never substitute a rectangular painted region for a segmentation result.
 */
internal object PersonInstancePolicy {
    const val MAX_PEOPLE = 12

    fun validated(detections: List<PersonDetection>): List<PersonDetection> {
        val output = detections.filter {
            it.id >= 0 && it.box.valid() && it.score.isFinite() && it.score >= .35f &&
                it.score <= 1f
        }.sortedByDescending { it.score }.take(MAX_PEOPLE)
        return output.mapIndexed { index, value -> value.copy(id = index) }
    }

    /** Several true foreground points: torso first, then upper and lower torso. */
    fun prompts(box: PersonBox): List<MaskPoint> {
        require(box.valid())
        return listOf(.5f to .45f, .5f to .3f, .5f to .65f,
            .35f to .52f, .65f to .52f).map { (rx, ry) ->
            MaskPoint(box.left + box.width * rx, box.top + box.height * ry)
        }
    }

    /**
     * Only accept actual SAM row-runs; never grow a mask into nearby people.
     * Expand the detector box modestly to preserve hair/arms at borders.
     * Fail closed when the selected mask does not substantially overlap its box.
     */
    fun constrainToPerson(strokes: List<MaskStroke>, box: PersonBox): List<MaskStroke> {
        if (!box.valid() || strokes.isEmpty()) throw IOException("Маска человека отсутствует.")
        val xPad = box.width * .12f
        val upperPad = box.height * .14f
        val lowerPad = box.height * .08f
        val left = max(0f, box.left - xPad)
        val right = min(1f, box.right + xPad)
        val top = max(0f, box.top - upperPad)
        val bottom = min(1f, box.bottom + lowerPad)
        val clipped = ArrayList<MaskStroke>(strokes.size)
        var hit = 0
        for (stroke in strokes) {
            if (stroke.erase || stroke.points.size < 2) continue
            val a = stroke.points.first()
            val b = stroke.points.last()
            // MobileSamGeometry.strokesFromLogits emits horizontal scanlines.
            if (!a.x.isFinite() || !b.x.isFinite() || !a.y.isFinite() ||
                !b.y.isFinite() || kotlin.math.abs(a.y - b.y) > .0001f) continue
            val y = a.y
            if (y !in top..bottom) continue
            val x0 = max(left, min(a.x, b.x))
            val x1 = min(right, max(a.x, b.x))
            if (x1 < x0) continue
            if (y in box.top..box.bottom && x1 >= box.left && x0 <= box.right) hit++
            clipped.add(stroke.copy(points = listOf(MaskPoint(x0, y), MaskPoint(x1, y))))
        }
        if (clipped.size < 8 || hit < 8) throw IOException(
            "MobileSAM не смогла выделить этого человека. Уточните фотографию.")
        return clipped
    }

    /**
     * Hair and the crown are frequently missed by a torso-centered SAM prompt.
     * Retry one upper prompt only if the accepted silhouette starts unusually
     * low inside the detector box. Never manufacture a head-shaped rectangle.
     */
    fun needsUpperDetailRetry(strokes: List<MaskStroke>, box: PersonBox): Boolean {
        if (!box.valid() || strokes.isEmpty()) return false
        val top = strokes.minOfOrNull { it.points.firstOrNull()?.y ?: 1f } ?: return false
        return top > box.top + box.height * .24f
    }

    /** A second real mask must add head coverage without exploding into background. */
    fun preferUpperDetail(
        current: List<MaskStroke>, alternative: List<MaskStroke>, box: PersonBox,
    ): Boolean {
        if (!box.valid() || current.isEmpty() || alternative.isEmpty()) return false
        val currentTop = current.minOf { it.points.first().y }
        val candidateTop = alternative.minOf { it.points.first().y }
        return currentTop - candidateTop >= box.height * .06f &&
            alternative.size * 2 >= current.size &&
            alternative.size <= current.size * 2
    }

    /**
     * Hit-test the actual segmentation row runs, not the detector's bbox.
     * A tap on unrelated background inside a large person bbox must not select it.
     * Radius is defined in source pixels, exactly as in the LaMa stroke rasterizer.
     */
    fun containsMask(
        person: PersonInstance, point: MaskPoint,
        imageWidth: Int, imageHeight: Int,
    ): Boolean {
        if (imageWidth <= 0 || imageHeight <= 0 ||
            !point.x.isFinite() || !point.y.isFinite() ||
            point.x !in 0f..1f || point.y !in 0f..1f) return false
        val x = point.x * imageWidth
        val y = point.y * imageHeight
        val shortest = min(imageWidth, imageHeight).toFloat()
        return person.strokes.any { stroke ->
            if (stroke.erase || stroke.points.size < 2) return@any false
            val a = stroke.points.first()
            val b = stroke.points.last()
            val ax = a.x * imageWidth
            val bx = b.x * imageWidth
            val rowY = a.y * imageHeight
            val dx = when {
                x < min(ax, bx) -> min(ax, bx) - x
                x > max(ax, bx) -> x - max(ax, bx)
                else -> 0f
            }
            val dy = y - rowY
            val radius = (stroke.diameter * shortest / 2f).coerceAtLeast(.5f)
            dx * dx + dy * dy <= radius * radius
        }
    }

    /** Avoid silently applying the same person-shaped mask to two overlapping detections. */
    fun ambiguity(left: PersonInstance, right: PersonInstance): Boolean {
        val a = left.detection.box; val b = right.detection.box
        val overlapX = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val overlapY = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val intersection = overlapX * overlapY
        val smallestArea = min(a.width * a.height, b.width * b.height)
        return smallestArea > 0f && intersection / smallestArea > .65f
    }
}

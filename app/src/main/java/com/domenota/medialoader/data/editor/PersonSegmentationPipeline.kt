package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class PersonScanResult(
    val detectedCount: Int,
    val people: List<PersonInstance>,
    val ambiguousIds: Set<Int>,
    val incompleteIds: Set<Int> = emptySet(),
) {
    val unsuccessful: Int get() = (detectedCount - people.size).coerceAtLeast(0)
}

/**
 * Actual two-model inference: EfficientDet detects distinct persons,
 * MobileSAM produces each person's pixel-level mask on the SAME source bitmap.
 * Never promote a detection box into an unverified person mask.
 */
internal class PersonSegmentationPipeline(
    private val detectPeople: suspend (Bitmap) -> List<PersonDetection>,
    private val segmentAt: suspend (Bitmap, MaskPoint) -> List<MaskStroke>,
) {
    constructor(detector: PersonDetector, mobileSam: MobileSamSegmenter) : this(
        detectPeople = { bitmap -> detector.detect(bitmap) },
        segmentAt = { bitmap, point -> mobileSam.segment(bitmap, point) },
    )

    suspend fun scan(bitmap: Bitmap): PersonScanResult {
        val detections = detectPeople(bitmap)
        if (detections.isEmpty()) return PersonScanResult(0, emptyList(), emptySet())
        val people = mutableListOf<PersonInstance>()
        for (detection in detections) {
            currentCoroutineContext().ensureActive()
            var mask: List<MaskStroke>? = null
            for (point in PersonInstancePolicy.prompts(detection.box)) {
                currentCoroutineContext().ensureActive()
                // A fully covered silhouette needs no further expensive
                // decoder calls; an isolated sweater must NOT end the scan.
                if (mask != null && !PersonInstancePolicy.incomplete(
                        requireNotNull(mask), detection.box)) break
                try {
                    val raw = segmentAt(bitmap, point)
                    val candidate = PersonInstancePolicy.constrainToPerson(raw, detection.box)
                    if (!PersonInstancePolicy.plausibleCandidate(candidate, detection.box))
                        continue
                    mask = when {
                        mask == null -> candidate
                        PersonInstancePolicy.addsDetail(requireNotNull(mask),
                            candidate, detection.box) -> requireNotNull(mask) + candidate
                        else -> mask
                    }
                } catch (_: IOException) {
                    // A prompt can land on clothing or background. Try another
                    // real mask; never replace it with an invented rectangle.
                }
            }
            if (mask != null) people += PersonInstance(detection, requireNotNull(mask))
        }
        val ambiguous = mutableSetOf<Int>()
        for (i in people.indices) for (j in i + 1 until people.size) {
            if (PersonInstancePolicy.ambiguity(people[i], people[j])) {
                ambiguous += people[i].detection.id
                ambiguous += people[j].detection.id
            }
        }
        val incomplete = people.filter {
            PersonInstancePolicy.incomplete(it.strokes, it.detection.box)
        }.mapTo(mutableSetOf()) { it.detection.id }
        return PersonScanResult(detections.size, people, ambiguous, incomplete)
    }
}

package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class PersonScanResult(
    val detectedCount: Int,
    val people: List<PersonInstance>,
    val ambiguousIds: Set<Int>,
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
                try {
                    val raw = segmentAt(bitmap, point)
                    mask = PersonInstancePolicy.constrainToPerson(raw, detection.box)
                    break
                } catch (_: IOException) {
                    // A prompt may land on the background; try other actual
                    // foreground candidates in THIS detected person's bbox.
                    // Incompatible weights remain fatal in MobileSAM itself.
                }
            }
            if (mask != null) people += PersonInstance(detection, mask)
        }
        val ambiguous = mutableSetOf<Int>()
        for (i in people.indices) for (j in i + 1 until people.size) {
            if (PersonInstancePolicy.ambiguity(people[i], people[j])) {
                ambiguous += people[i].detection.id
                ambiguous += people[j].detection.id
            }
        }
        return PersonScanResult(detections.size, people, ambiguous)
    }
}

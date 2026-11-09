package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Color
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PersonSegmentationPipelineTest {
    private val source = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.BLUE)
    }
    private val boxes = listOf(
        PersonDetection(0, PersonBox(.1f, .1f, .40f, .85f), .92f),
        PersonDetection(1, PersonBox(.6f, .1f, .90f, .85f), .85f),
    )
    private fun segments(point: MaskPoint): List<MaskStroke> {
        val x = if (point.x < .5f) .14f to .38f else .62f to .87f
        return (15..53).map { row ->
            val y = row / 64f
            MaskStroke(listOf(MaskPoint(x.first, y), MaskPoint(x.second, y)), .026f)
        }
    }

    @Test fun noPeopleReturnNoMasksAndNoSegmentInference() = runBlocking {
        var segmentCalls = 0
        val pipeline = PersonSegmentationPipeline(
            detectPeople = { emptyList() },
            segmentAt = { _, _ -> segmentCalls++; emptyList() },
        )
        val result = pipeline.scan(source)
        assertEquals(0, result.detectedCount)
        assertEquals(0, result.people.size)
        assertEquals(0, segmentCalls)
    }

    @Test fun distinctPersonsProduceSeparatePixelMasksAndCanRemoveOnlyOne() = runBlocking {
        val pipeline = PersonSegmentationPipeline(
            detectPeople = { boxes },
            segmentAt = { _, point -> segments(point) },
        )
        val found = pipeline.scan(source)
        assertEquals(2, found.detectedCount)
        assertEquals(2, found.people.size)
        assertTrue(found.ambiguousIds.isEmpty())

        val first = found.people[0].strokes
        val second = found.people[1].strokes
        assertTrue(first.all { it.points.all { p -> p.x < .5f } })
        assertTrue(second.all { it.points.all { p -> p.x > .5f } })

        var inpaintCalled = 0
        val engine = LaMaInpaintingEngine(InpaintingModelRunner { image, mask ->
            inpaintCalled++
            assertTrue(mask.any { it > 0 })
            FloatArray(image.size).also { output ->
                for (i in 0 until 512 * 512) output[i] = 255f
            }
        })
        val edited = engine.inpaint(source, first, InpaintingSettings())
        try {
            assertEquals(1, inpaintCalled)
            assertEquals(Color.RED, edited.getPixel(34, 60))
            assertEquals(Color.BLUE, edited.getPixel(98, 60))
            assertEquals(Color.BLUE, source.getPixel(34, 60))
        } finally {
            edited.recycle()
        }
    }

    @Test fun failedSegmentationDoesNotCreateFakePersonMask() = runBlocking {
        val pipeline = PersonSegmentationPipeline(
            detectPeople = { boxes.take(1) },
            segmentAt = { _, _ -> throw IOException("Unusable foreground") },
        )
        val result = pipeline.scan(source)
        assertEquals(1, result.detectedCount)
        assertEquals(0, result.people.size)
        assertEquals(1, result.unsuccessful)
    }
}

package com.domenota.medialoader.data.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrLineCandidatePolicyTest {
    private fun letter(
        id: Int, left: Float, top: Float, confidence: Float = 82f,
    ) = DetectedGlyph(id, "a", left, top, left + .009f, top + .018f, confidence)

    @Test fun tinyBottomLeftWatermarkBeatsScatteredFaceAndHairNoise() {
        val watermark = (0 until 15).map { i ->
            letter(i, .03f + i * .013f, .945f)
        }
        val falsePositives = listOf(
            letter(17, .55f, .20f),
            letter(18, .61f, .28f),
            letter(19, .44f, .35f),
            letter(20, .76f, .41f),
            letter(21, .42f, .49f),
            letter(22, .65f, .64f),
        )
        val chosen = OcrLineCandidatePolicy.best(
            falsePositives, listOf(watermark))
        assertEquals(15, chosen.size)
        assertTrue(chosen.all { it.top > .9f && it.left < .3f })
        assertEquals((0 until 15).toList(), chosen.map { it.id })
    }

    @Test fun isolatedHighConfidenceHairSymbolsCannotBeAutoSelected() {
        val random = listOf(
            letter(1, .35f, .19f, 99f),
            letter(2, .43f, .27f, 99f),
            letter(3, .55f, .32f, 99f),
        )
        assertTrue(OcrLineCandidatePolicy.select(random).isEmpty())
        assertTrue(OcrLineCandidatePolicy.best(random, listOf(emptyList())).isEmpty())
    }

    @Test fun lowConfidenceDecorativeShapesFailClosedEvenInACluster() {
        val shaky = (0 until 13).map { i ->
            letter(i, .1f + i * .012f, .94f, confidence = 41f)
        }
        assertTrue(OcrLineCandidatePolicy.select(shaky).isEmpty())
    }

    @Test fun fullyVisibleCaptionOutsideBottomCropCanStillBeSelected() {
        val title = (0 until 8).map { i ->
            letter(i, .28f + .02f * i, .19f, confidence = 87f)
        }
        val chosen = OcrLineCandidatePolicy.best(title, listOf(emptyList()))
        assertEquals(8, chosen.size)
        assertTrue(chosen.all { it.top < .3f })
    }
}

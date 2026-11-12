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

    @Test fun weakFullFrameClusterDoesNotAutoEraseFaceWhenCaptionMissing() {
        val cheekTexture = (0 until 5).map { i ->
            letter(i, .32f + .012f * i, .35f, confidence = 68f)
        }
        assertTrue(OcrLineCandidatePolicy.best(
            cheekTexture, listOf(emptyList())).isEmpty())
    }

    @Test fun distinctStrongHeadingAndBottomWatermarkSurviveDifferentPasses() {
        val heading = (0 until 4).map { i ->
            DetectedGlyph(i, "Тест"[i].toString(), .31f + i * .055f, .75f,
                .36f + i * .055f, .83f, 96f)
        }
        val watermark = (0 until 15).map { i ->
            letter(10 + i, .03f + i * .013f, .945f, 84f)
        }
        val fragment = watermark.take(6).map { it.copy(confidence = 98f) }
        val chosen = OcrLineCandidatePolicy.best(heading, listOf(fragment, watermark))
        assertEquals(19, chosen.size)
        assertEquals(4, chosen.count { it.top < .9f })
        assertEquals(15, chosen.count { it.top > .9f })
        assertEquals((0 until 19).toList(), chosen.map { it.id })
    }

    @Test fun overlappingHypothesesAreNotDuplicateMasksAndUrlPunctuationIsKept() {
        val symbols = "link.tr".mapIndexed { i, ch ->
            letter(i, .02f + i * .013f, .94f, 90f).copy(text = ch.toString())
        }
        val duplicate = symbols[1].copy(id = 99, confidence = 70f)
        val selected = OcrLineCandidatePolicy.select(symbols + duplicate)
        assertEquals(symbols.size, selected.size)
        assertEquals(1, selected.count { it.text == "." })
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

package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PersonInstancePolicyTest {
    private val left = PersonBox(.08f, .10f, .42f, .85f)
    private val right = PersonBox(.58f, .12f, .9f, .88f)

    @Test fun zeroOneTwoPersonsHaveIndependentStableIds() {
        assertTrue(PersonInstancePolicy.validated(emptyList()).isEmpty())
        val two = PersonInstancePolicy.validated(listOf(
            PersonDetection(33, right, .61f),
            PersonDetection(52, left, .89f),
        ))
        assertEquals(listOf(0, 1), two.map { it.id })
        assertEquals(left, two.first().box)
        assertEquals(right, two.last().box)
    }

    @Test fun invalidBoxesAndLowScoresFailClosed() {
        val invalid = PersonInstancePolicy.validated(listOf(
            PersonDetection(1, PersonBox(Float.NaN, 0f, .8f, .7f), .95f),
            PersonDetection(2, PersonBox(.7f, .5f, .3f, .9f), .91f),
            PersonDetection(3, left, .12f),
            PersonDetection(4, left, .9f),
        ))
        assertEquals(1, invalid.size)
        assertEquals(left, invalid[0].box)
    }

    @Test fun promptCandidatesStayWithinTheirPersonBox() {
        for (box in listOf(left, right)) {
            assertEquals(5, PersonInstancePolicy.prompts(box).size)
            assertTrue(PersonInstancePolicy.prompts(box).all(box::contains))
        }
    }

    @Test fun segmentationCannotBecomeAnOpaquePaintedRectangle() {
        // Mask supplied by SAM: 25 horizontal source-space scanline runs.
        val strokes = (20..44).map { row ->
            val y = row / 64f
            MaskStroke(listOf(MaskPoint(.12f, y), MaskPoint(.38f, y)), .03f)
        }
        val leftMask = PersonInstancePolicy.constrainToPerson(strokes, left)
        assertEquals(25, leftMask.size)
        assertTrue(leftMask.all { it.points.all { p -> p.x < .5f } })
        try {
            PersonInstancePolicy.constrainToPerson(strokes, right)
            fail("A left person's mask cannot be reused for a person on the right.")
        } catch (_: IOException) { /* expected */ }
    }

    @Test fun tapSelectsActualSilhouetteNotBackgroundInsidePersonBox() {
        val box = PersonBox(.1f, .1f, .9f, .9f)
        val strokes = listOf(MaskStroke(listOf(
            MaskPoint(.35f, .5f), MaskPoint(.58f, .5f)), .05f))
        val person = PersonInstance(PersonDetection(0, box, .9f), strokes)
        assertTrue(box.contains(MaskPoint(.84f, .5f)))
        assertFalse(PersonInstancePolicy.containsMask(
            person, MaskPoint(.84f, .5f), 200, 200))
        assertTrue(PersonInstancePolicy.containsMask(
            person, MaskPoint(.5f, .5f), 200, 200))
        assertFalse(PersonInstancePolicy.containsMask(
            person, MaskPoint(.05f, .5f), 200, 200))
    }

    @Test fun highlyOverlappingDetectionsCannotBeSelectedIndependently() {
        val strokes = (0..30).map {
            MaskStroke(listOf(MaskPoint(.2f, it / 64f),
                MaskPoint(.3f, it / 64f)), .02f)
        }
        val a = PersonInstance(PersonDetection(0, left, .9f), strokes)
        val b = PersonInstance(PersonDetection(1,
            PersonBox(.12f, .12f, .40f, .80f), .8f), strokes)
        val c = PersonInstance(PersonDetection(2, right, .8f), strokes)
        assertTrue(PersonInstancePolicy.ambiguity(a, b))
        assertFalse(PersonInstancePolicy.ambiguity(a, c))
    }
    @Test fun safeHeadPromptCanPreserveHairAboveDetectorBox() {
        val box = PersonBox(.2f, .2f, .6f, .8f)
        val strokes = (10..40).map { row ->
            val y = row / 100f
            MaskStroke(listOf(MaskPoint(.30f, y), MaskPoint(.48f, y)), .01f)
        }
        val mask = PersonInstancePolicy.constrainToPerson(strokes, box)
        assertTrue("Hair just above the box should remain", mask.any { it.points.first().y == .13f })
        assertTrue("Far outside detector bounds must still be rejected",
            mask.none { it.points.first().y == .10f })
    }

    @Test fun upperSilhouetteRetryOnlyAcceptsMeaningfulHeadCoverage() {
        val box = PersonBox(.1f, .1f, .5f, .9f)
        fun rows(begin: Int, end: Int): List<MaskStroke> =
            (begin..end).map { y ->
                MaskStroke(listOf(MaskPoint(.2f, y / 100f),
                    MaskPoint(.4f, y / 100f)), .01f)
            }
        val torso = rows(39, 70)
        val complete = rows(17, 72)
        val backgroundExplosion = rows(10, 95) + rows(10, 95)
        assertTrue(PersonInstancePolicy.needsUpperDetailRetry(torso, box))
        assertFalse(PersonInstancePolicy.needsUpperDetailRetry(complete, box))
        assertTrue(PersonInstancePolicy.preferUpperDetail(torso, complete, box))
        assertFalse(PersonInstancePolicy.preferUpperDetail(torso, backgroundExplosion, box))
        assertFalse(PersonInstancePolicy.preferUpperDetail(complete, torso, box))
    }

    @Test fun partialTorsoIsNotCertifiedAsWholePerson() {
        val box = PersonBox(.10f, .10f, .90f, .95f)
        fun stripes(first: Int, last: Int) = (first..last).map { y ->
            MaskStroke(listOf(MaskPoint(.28f, y / 100f),
                MaskPoint(.68f, y / 100f)), .012f)
        }
        val sweater = stripes(35, 63)
        val head = stripes(13, 38)
        val legs = stripes(60, 92)
        assertTrue(PersonInstancePolicy.incomplete(sweater, box))
        assertTrue(PersonInstancePolicy.addsDetail(sweater, head, box))
        assertTrue(PersonInstancePolicy.addsDetail(sweater + head, legs, box))
        assertFalse(PersonInstancePolicy.incomplete(sweater + head + legs, box))
        assertFalse(PersonInstancePolicy.addsDetail(sweater, sweater, box))
    }

    @Test fun broadBackgroundCandidateCannotBeMergedIntoPerson() {
        val box = PersonBox(.10f, .10f, .90f, .95f)
        val sweater = (35..63).map { y ->
            MaskStroke(listOf(MaskPoint(.28f, y / 100f),
                MaskPoint(.68f, y / 100f)), .012f)
        }
        val wall = (14..85).map { y ->
            MaskStroke(listOf(MaskPoint(.11f, y / 100f),
                MaskPoint(.89f, y / 100f)), .012f)
        }
        assertFalse(PersonInstancePolicy.plausibleCandidate(wall, box))
        assertFalse(PersonInstancePolicy.addsDetail(sweater, wall, box))
        assertTrue(PersonInstancePolicy.plausibleCandidate(sweater, box))
    }

}

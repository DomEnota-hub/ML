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
}

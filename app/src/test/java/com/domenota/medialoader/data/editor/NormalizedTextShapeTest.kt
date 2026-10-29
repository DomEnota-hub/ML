package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class NormalizedTextShapeTest {
    private fun shape(shift: Int = 0, serif: Boolean = false, width: Int = 3): NormalizedTextShape {
        val mask = BooleanArray(256 * 64)
        for (stem in listOf(20, 65, 105, 145, 190, 225)) {
            for (y in 6..57) for (x in stem + shift until stem + shift + width) mask[y * 256 + x] = true
            if (serif) for (y in listOf(6, 7, 56, 57)) for (x in stem - 5..stem + 7) mask[y * 256 + x] = true
        }
        return NormalizedTextShape(mask, 4f)
    }
    @Test fun ranksMatchingShapeAboveDifferentFamilyAndWeight() {
        val reference = shape(serif = true)
        assertEquals(0f, reference.distanceTo(shape(serif = true)), 0f)
        assertTrue(reference.distanceTo(shape(1, serif = true)) < reference.distanceTo(shape(width = 12)))
        assertTrue(reference.distanceTo(shape(serif = true)) < reference.distanceTo(shape()))
        val sans = shape()
        assertTrue(sans.distanceTo(shape()) < sans.distanceTo(shape(serif = true)))
    }
    @Test fun trimsSampleMarginsWithoutChangingGlyphProportions() {
        val mask = BooleanArray(100 * 40) { index -> index % 100 in 10..59 && index / 100 in 10..29 }
        val tight = TextRaster(100, 40, mask).tight()
        assertEquals(50, tight.width)
        assertEquals(20, tight.height)
        assertTrue(tight.bits.all { it })
        assertEquals(2.5f, tight.normalized().aspect, 0f)
    }
}

package com.domenota.medialoader.data.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonContourOverlayTest {
    @Test fun onlyTrueSilhouetteBoundaryIsPainted() {
        val w = 7
        val h = 7
        val cover = IntArray(w * h)
        for (y in 2..4) for (x in 2..4) cover[y * w + x] = -1
        val yellow = 0xFFFFC21C.toInt()
        val outline = PersonContourOverlay.contour(cover, w, h, yellow)
        assertEquals(0, outline[3 * w + 3])
        assertEquals(yellow, outline[2 * w + 3])
        assertEquals(yellow, outline[3 * w + 2])
        assertEquals(0, outline[1 * w + 3])
        assertEquals(8, outline.count { it != 0 })
    }

    @Test fun disconnectedForegroundHasIndependentOutlines() {
        val w = 5
        val h = 3
        val cover = IntArray(w * h)
        cover[1 * w + 0] = -1
        cover[1 * w + 4] = -1
        val out = PersonContourOverlay.contour(cover, w, h, 0xFFABCDEF.toInt())
        assertEquals(2, out.count { it != 0 })
        assertTrue(out.slice(1 * w + 1 until 1 * w + 4).all { it == 0 })
    }
    @Test fun selectedPersonHasVisibleFillOnlyInsideRealMask() {
        val width = 7
        val height = 7
        val coverage = IntArray(width * height)
        for (y in 2..4) for (x in 2..4) coverage[y * width + x] = -1
        val edge = 0xFFFFC21C.toInt()
        val preview = PersonContourOverlay.paintCoverage(
            coverage, width, height, selected = true, edgeArgb = edge)
        assertEquals(edge, preview[2 * width + 3])
        assertEquals(edge, preview[3 * width + 2])
        assertEquals(0, preview[1 * width + 3])
        val interior = preview[3 * width + 3]
        assertEquals(0xFFC21C, interior and 0xFFFFFF)
        assertTrue("Selected interior must be translucent", (interior ushr 24) in 64..160)
    }

    @Test fun unselectedAndAmbiguousCandidatesStayOutlineOnly() {
        val width = 7
        val height = 7
        val coverage = IntArray(width * height)
        for (y in 2..4) for (x in 2..4) coverage[y * width + x] = -1
        val outline = PersonContourOverlay.paintCoverage(
            coverage, width, height, selected = false, edgeArgb = 0xFF919191.toInt())
        assertEquals(0, outline[3 * width + 3])
        assertEquals(8, outline.count { it != 0 })
    }
}


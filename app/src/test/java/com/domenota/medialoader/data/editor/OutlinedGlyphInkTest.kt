package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class OutlinedGlyphInkTest {
    private val w = 30; private val h = 32
    private val white = -1; private val black = 0xff000000.toInt()
    private fun scene() = IntArray(w*h) { i ->
        if ((i/w + i%w) % 2 == 0) 0xff9f785c.toInt() else 0xff648f9f.toInt()
    }
    private fun paint(pixels: IntArray): BooleanArray {
        val expected = BooleanArray(pixels.size)
        for (y in 6..25) for (x in 7..22) {
            val edge = minOf(x-7,22-x,y-6,25-y)
            if (edge < 4) {
                pixels[y*w+x] = if (edge == 0 || edge == 3) black else white
                expected[y*w+x] = true
            }
        }
        return expected
    }
    @Test fun outlinedFillAndStrokeOnMixedBackgroundPreserveHole() {
        val pixels = scene(); val expected = paint(pixels)
        val result = OutlinedGlyphInk.extract(pixels,w,h)
        assertNotNull(result)
        assertArrayEquals(expected,result!!)
    }
    @Test fun connectedDoorEdgeIsNotErased() {
        val pixels = scene(); paint(pixels)
        for (y in 0..6) pixels[y*w+14] = black
        assertNull(OutlinedGlyphInk.extract(pixels,w,h))
    }
    @Test fun secondUnresolvedInteriorFillRejectsWholeGlyph() {
        val pixels = scene(); paint(pixels)
        for (y in 13..17) for (x in 12..17) pixels[y*w+x] = white
        assertNull(OutlinedGlyphInk.extract(pixels,w,h))
    }
    @Test fun unoutlinedTextureAndCheckerboardAreRejected() {
        assertNull(OutlinedGlyphInk.extract(scene(),w,h))
        assertNull(OutlinedGlyphInk.extract(IntArray(w*h) {
            if ((it/w + it%w)%2==0) white else black
        },w,h))
    }
}

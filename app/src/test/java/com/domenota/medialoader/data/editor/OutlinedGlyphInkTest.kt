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
    @Test fun sharedOutlineAndAntialiasAreVerifiedAcrossAdjacentBoxes() {
        val width=48;val height=32
        val pixels=IntArray(width*height){0xff967050.toInt()}
        val expected=BooleanArray(pixels.size)
        for (left in listOf(8,23)) for(y in 7..24) for(x in left..left+15) {
            val edge=minOf(x-left,left+15-x,y-7,24-y)
            pixels[y*width+x]=when { edge==0 -> black; x==left+1 && y in 9..22 -> 0xff909090.toInt(); else -> white }
            expected[y*width+x]=true
        }
        val regions=listOf(OutlinedGlyphInk.Region(9,8,23,24,3),OutlinedGlyphInk.Region(24,8,38,24,3))
        val result=OutlinedGlyphInk.extractRegions(pixels,width,height,regions)!!
        assertEquals(setOf(0,1),result.verified)
        assertArrayEquals(expected,BooleanArray(pixels.size){result.owner[it]>=0})
    }
    @Test fun seamRejectsSharedOutlineButPreservesIndependentGlyph() {
        val width=76;val height=32
        val pixels=IntArray(width*height){0xff967050.toInt()}
        for(left in listOf(8,23,53)) for(y in 7..24) for(x in left..left+15) {
            val edge=minOf(x-left,left+15-x,y-7,24-y)
            pixels[y*width+x]=if(edge==0)black else white
        }
        for(y in 0..7)pixels[y*width+14]=black
        val regions=listOf(OutlinedGlyphInk.Region(9,8,23,24,2),OutlinedGlyphInk.Region(24,8,38,24,2),OutlinedGlyphInk.Region(54,8,68,24,2))
        val result=OutlinedGlyphInk.extractRegions(pixels,width,height,regions)!!
        assertEquals(setOf(2),result.verified)
        assertEquals(288,result.owner.count{it==2})
        assertTrue(result.owner.none{it==0||it==1})
    }
}

package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class PhotoMarkEraserRegressionTest {
    @Test fun eraserSplitsLineLocallyInsteadOfDeletingIt() {
        val stroke = PhotoMark(PhotoMarkKind.PEN, listOf(
            PhotoPoint(.1f,.5f), PhotoPoint(.9f,.5f)), thickness=.008f)
        val output = PhotoMarkEdits.eraseNear(listOf(stroke), PhotoPoint(.5f,.5f), .045f)
        assertTrue("Both remaining fragments should survive", output.size >= 2)
        assertTrue(output.first().points.first().x <= .1f + .001f)
        assertTrue(output.last().points.last().x >= .9f - .001f)
        assertTrue(output.all { it.points.none { p -> kotlin.math.abs(p.x-.5f) < .035f } })
    }

    @Test fun eraserPreservesUnrelatedMarks() {
        val first = PhotoMark(PhotoMarkKind.PEN, listOf(PhotoPoint(.1f,.1f),PhotoPoint(.9f,.1f)))
        val second = PhotoMark(PhotoMarkKind.MARKER, listOf(PhotoPoint(.1f,.8f),PhotoPoint(.9f,.8f)))
        val result = PhotoMarkEdits.eraseNear(listOf(first,second), PhotoPoint(.5f,.8f), .03f)
        assertEquals(first, result.first())
        assertTrue(result.size >= 3)
    }

    @Test fun eraserAwayFromAllStrokesDoesNotAlterDocument() {
        val stroke = PhotoMark(PhotoMarkKind.NEON, listOf(PhotoPoint(.1f,.1f),PhotoPoint(.2f,.1f)))
        val original = listOf(stroke)
        assertEquals(original, PhotoMarkEdits.eraseNear(original, PhotoPoint(.9f,.9f),.02f))
    }
}

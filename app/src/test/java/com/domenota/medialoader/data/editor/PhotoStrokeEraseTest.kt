package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class PhotoStrokeEraseTest {
    private fun point(x: Float, y: Float) = PhotoPoint(x, y)

    @Test fun crossingStrokeSplitsIntoPersistentGeometryInsteadOfClearMask() {
        val stroke = PhotoMark(PhotoMarkKind.PEN, listOf(point(.1f, .5f), point(.9f, .5f)),
            thickness = .008f)
        val result = PhotoStrokeErase.apply(listOf(stroke), listOf(point(.5f, .5f)),
            eraserThickness = .12f, imageWidth = 1000, imageHeight = 1000)
        assertTrue(result.size >= 2)
        assertTrue(result.all { it.kind != PhotoMarkKind.ERASER })
        assertTrue(result.any { fragment -> fragment.points.any { it.x < .4f } })
        assertTrue(result.any { fragment -> fragment.points.any { it.x > .6f } })
        assertTrue(result.flatMap { it.points }.none { it.x in .45f..0.55f && it.y in .45f..55f })
    }

    @Test fun erasingUntouchedStrokeDoesNotChangeItsGeometry() {
        val line = PhotoMark(PhotoMarkKind.PEN, listOf(point(.1f, .1f), point(.9f, .1f)))
        assertEquals(listOf(line), PhotoStrokeErase.apply(listOf(line),
            listOf(point(.5f, .8f)), .15f, 1000, 1000))
    }

    @Test fun eraserRangeIsThreeTimesOrdinaryBrushMaximum() {
        assertEquals(3f * PhotoDrawing.MAX_BRUSH_THICKNESS,
            PhotoDrawing.MAX_ERASER_THICKNESS, .00001f)
        assertTrue(PhotoDrawing.MAX_MARK_THICKNESS >= PhotoDrawing.MAX_ERASER_THICKNESS)
    }

    @Test fun tappingEraserAndStrokesRemainWithinMarkLimit() {
        val stroke = PhotoMark(PhotoMarkKind.MARKER, listOf(point(.45f, .5f), point(.55f, .5f)))
        val result = PhotoStrokeErase.apply(listOf(stroke), listOf(point(.5f, .5f)),
            .15f, 1000, 1000)
        assertTrue(result.isEmpty())
        assertTrue(result.size <= PhotoDrawing.MAX_MARKS)
    }
}

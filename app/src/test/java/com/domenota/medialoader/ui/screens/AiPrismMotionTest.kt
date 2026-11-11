package com.domenota.medialoader.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPrismMotionTest {
    @Test fun referenceCycleHoldsBothFullFaces() {
        val white = AiPrismMotion.frame(.10f)
        assertTrue(white.faceIsWhite)
        assertTrue(white.faceWidth > .99f)
        assertTrue(white.sideWidth < .01f)

        val black = AiPrismMotion.frame(.50f)
        assertFalse(black.faceIsWhite)
        assertTrue(black.faceWidth > .99f)
        assertTrue(black.sideWidth < .01f)
    }

    @Test fun edgeOnProjectionKeepsActualRectangularSideWidth() {
        for (t in listOf(.30f, .70f)) {
            val edge = AiPrismMotion.frame(t)
            assertTrue("Face must turn edge-on", edge.faceWidth < .03f)
            assertTrue("A thick side must be visible, never an invisible needle",
                edge.sideWidth >= .23f)
        }
    }

    @Test fun reverseTurnRestoresWhiteFaceWithoutGeometryJump() {
        val leavingBlack = AiPrismMotion.frame(.64f)
        val returningWhite = AiPrismMotion.frame(.75f)
        assertTrue(leavingBlack.sideWidth > .10f)
        assertTrue(returningWhite.sideWidth > .10f)
        assertTrue(AiPrismMotion.frame(.90f).faceIsWhite)
    }
}

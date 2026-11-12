package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class LargePersonBackgroundRecoveryTest {
    private val w = 100
    private val h = 120
    private fun color(r: Int, g: Int, b: Int): Int =
        0xff000000.toInt() or (r shl 16) or (g shl 8) or b
    private fun background(x: Int, y: Int): Int {
        val level = 174 + x / 4 + y / 5
        return color(level, level + 3, level + 7)
    }
    private fun mask() = IntArray(w * h) { i ->
        if (i % w in 19..80 && i / w in 20..99) 255 else 0
    }

    @Test fun removesLargeDarkGhostOnlyInsideVerifiedMaskOnSmoothBackground() {
        val coverage = mask()
        val source = IntArray(w * h) { i ->
            if (coverage[i] > 0) color(30, 36, 44) else background(i % w, i / w)
        }
        val failed = source.copyOf()
        for (i in failed.indices) if (coverage[i] > 0) failed[i] = color(75, 65, 64)
        val recovered = requireNotNull(LargePersonBackgroundRecovery.recover(source, coverage, w, h, failed))
        for (i in recovered.indices) {
            if (coverage[i] == 0) assertEquals(source[i], recovered[i])
            else {
                val expected = background(i % w, i / w)
                for (shift in listOf(0, 8, 16)) assertTrue(
                    abs(((recovered[i] ushr shift) and 255) - ((expected ushr shift) and 255)) <= 3)
            }
        }
    }

    @Test fun alreadyGoodModelResultIsNotReplaced() {
        val coverage = mask()
        val source = IntArray(w * h) { i ->
            if (coverage[i] > 0) color(30, 36, 44) else background(i % w, i / w)
        }
        val plausible = IntArray(w * h) { i -> background(i % w, i / w) }
        assertNull(LargePersonBackgroundRecovery.recover(source, coverage, w, h, plausible))
    }

    @Test fun complexContextOrSmallMaskNeverTriggersUniformRecovery() {
        val coverage = mask()
        val textured = IntArray(w * h) { i ->
            if ((i % w / 5 + i / w / 5) % 2 == 0) color(15, 45, 220) else color(235, 220, 30)
        }
        assertNull(LargePersonBackgroundRecovery.recover(textured, coverage, w, h, textured))
        val small = IntArray(w * h) { i -> if (i % w in 40..60 && i / w in 50..70) 255 else 0 }
        val smooth = IntArray(w * h) { i -> background(i % w, i / w) }
        assertNull(LargePersonBackgroundRecovery.recover(smooth, small, w, h, textured))
    }

    @Test fun periodicTextureAliasingTheSamplingGridIsStillRejected() {
        val width = 400
        val height = 400
        val coverage = IntArray(width * height) { i ->
            if (i % width in 75..324 && i / width in 65..334) 255 else 0
        }
        // step=4: every sampled anchor has the same colour, but the
        // actual unmasked context is a high-contrast checkerboard.
        val source = IntArray(width * height) { i ->
            if (coverage[i] > 0) color(30, 36, 44)
            else if ((i % width + i / width) % 2 == 0)
                color(200, 204, 210) else color(25, 30, 35)
        }
        val failed = source.copyOf()
        for (i in failed.indices) if (coverage[i] > 0) failed[i] = color(35, 35, 35)
        assertNull(LargePersonBackgroundRecovery.recover(source, coverage, width, height, failed))
    }

    @Test fun contextFromOnlyOneSideCannotJustifyRecovery() {
        val coverage = IntArray(w * h) { i -> if (i / w < 75) 255 else 0 }
        val source = IntArray(w * h) { i -> if (coverage[i] > 0) color(25, 25, 25)
            else background(i % w, i / w) }
        assertNull(LargePersonBackgroundRecovery.recover(source, coverage, w, h, source))
    }
}

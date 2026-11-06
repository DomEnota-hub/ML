package com.domenota.medialoader.ui.screens.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MagneticContourTracerTest {
    @Test fun contourSnapsNearSegmentationBoundaryAndFollowsFinger() {
        val source = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val mask = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(25, 30, 35))
        mask.eraseColor(Color.TRANSPARENT)
        val face = Paint().apply { color = Color.rgb(220, 190, 155) }
        val alpha = Paint().apply { color = Color.WHITE }
        Canvas(source).drawRect(16f, 16f, 80f, 80f, face)
        Canvas(mask).drawRect(16f, 16f, 80f, 80f, alpha)
        try {
            val tracer = MagneticContourTracer(source, mask)
            val start = tracer.snap(Offset(30f, 19f), null, 95f)
            assertTrue("Nearest horizontal edge found: $start", abs(start.y - 16f) < 5f)
            val trail = tracer.follow(start, Offset(65f, 18.5f), 85f)
            assertTrue(trail.size >= 5)
            assertTrue("Follows real edge: ${trail.last()}", abs(trail.last().y - 16f) < 6f)
            assertTrue(trail.last().x >= 59f)
        } finally {
            source.recycle(); mask.recycle()
        }
    }

    @Test fun weakOrZeroAttractionNeverTeleportsAcrossPlainImage() {
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val mask = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(130, 130, 130))
        mask.eraseColor(Color.WHITE)
        try {
            val tracer = MagneticContourTracer(source, mask)
            val point = Offset(32.25f, 28.75f)
            assertEquals(point, tracer.snap(point, null, 100f))
            assertEquals(point, tracer.snap(point, null, 0f))
            val samples = tracer.follow(Offset(9f, 28f), Offset(53f, 28f), 95f)
            assertTrue(samples.isNotEmpty())
            assertTrue(samples.all { abs(it.y - 28f) < .01f })
        } finally {
            source.recycle(); mask.recycle()
        }
    }

    @Test fun outOfBoundsGestureRemainsInsideImageWithoutNonFinitePoints() {
        val source = Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888)
        val mask = Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        mask.eraseColor(Color.TRANSPARENT)
        try {
            val tracer = MagneticContourTracer(source, mask)
            val snapped = tracer.snap(Offset(-12f, 900f), null, 75f)
            assertTrue(snapped.x in 0f..19f && snapped.y in 0f..39f)
            assertTrue(tracer.follow(Offset(3f, 6f), Offset(15f, 26f), 65f).isNotEmpty())
        } finally {
            source.recycle(); mask.recycle()
        }
    }
}

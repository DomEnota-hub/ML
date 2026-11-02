package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Regression guard against the old editor's decorative effect choices.
 * A device/codec MP4 golden test remains required for final release signoff.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoSpecialFxMedia3WiringTest {

    @Test fun allRealFxTypesGenerateNonemptyMedia3VideoPipeline() {
        val editor = VideoEditor(RuntimeEnvironment.getApplication())
        val real = VideoSpecialFxType.entries.filterNot { it == VideoSpecialFxType.NONE }
        assertEquals("A catalog with 4 effects is incomplete", 10, real.size)
        for (type in real) {
            val effects = editor.previewEffects(
                VideoEditSpec(specialFx = VideoSpecialFxDraft(type, 0.75f)))
            assertTrue("${type.label} must affect actual Media3 output",
                effects.any { it is OverlayEffect })
        }
        assertFalse(editor.previewEffects(VideoEditSpec()).any { it is OverlayEffect })
    }

    @Test fun everyEffectChangesPixelsAndAllThumbnailsDiffer() {
        val source = Bitmap.createBitmap(144, 94, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(116, 79, 42))
        val baseline = fingerprint(source)
        val signatures = mutableListOf<Long>()
        for (type in VideoSpecialFxType.entries.filterNot {
            it == VideoSpecialFxType.NONE
        }) {
            val actual = Bitmap.createBitmap(144, 94, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(actual)
            canvas.drawBitmap(source, 0f, 0f, null)
            VideoSpecialFxPainter.draw(canvas, 144, 94,
                VideoSpecialFxDraft(type, 0.75f), stillPreview = true)
            val signature = fingerprint(actual)
            assertNotEquals("${type.label} must be visibly different from input",
                baseline, signature)
            signatures += signature
            actual.recycle()
        }
        assertEquals("Every effect must have its own visible image",
            signatures.size, signatures.distinct().size)
        source.recycle()
    }

    @Test fun sliderReallyControlsEffectIntensity() {
        val source = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(93, 105, 132))
        for (type in VideoSpecialFxType.entries.filterNot {
            it == VideoSpecialFxType.NONE
        }) {
            fun make(intensity: Float): Long {
                val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
                VideoSpecialFxPainter.draw(Canvas(bitmap), bitmap.width, bitmap.height,
                    VideoSpecialFxDraft(type, intensity), stillPreview = true)
                return fingerprint(bitmap).also { bitmap.recycle() }
            }
            assertEquals("${type.label}: zero strength must not modify pixels",
                fingerprint(source), make(0f))
            assertNotEquals("${type.label}: 20% and 90% must differ",
                make(0.2f), make(0.9f))
        }
        source.recycle()
    }

    private fun fingerprint(bitmap: Bitmap): Long {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.fold(17L) { hash, pixel -> hash * 31L + pixel }
    }
}

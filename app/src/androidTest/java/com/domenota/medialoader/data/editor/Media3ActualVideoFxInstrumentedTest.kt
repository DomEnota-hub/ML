package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual video codec + Media3 Transformer integration, not just JVM bitmap
 * previews. Tiny real MP4 fixture: white H264/YUV420p 160x90, 12 frames/0.8s.
 * The output MUST contain black cinema bars AND retain a white center frame.
 *
 * This detects the previous regression where an effect appeared selectable
 * but was not applied to an exported MP4. No user files are read or modified.
 */
@RunWith(AndroidJUnit4::class)
class Media3ActualVideoFxInstrumentedTest {
    @Test fun cinemaBarsAreActuallyBakedIntoMp4() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.cacheDir, "medialoader-fx-instrumented-source.mp4")
        var output: File? = null
        try {
            // The fixture belongs to the androidTest APK, not the target app.
            val encoded = InstrumentationRegistry.getInstrumentation().context.assets
                .open("fx_white_h264_160x90_800ms.base64")
                .bufferedReader().use { it.readText().trim() }
            fixture.writeBytes(Base64.decode(encoded, Base64.DEFAULT))
            assertTrue("Fixture MP4 must exist", fixture.length() > 1000L)

            // Decode the fixture BEFORE invoking Transformer so a corrupt source is
            // reported separately from an effects/codec failure.
            readFrame(fixture).recycle()
            output = withTimeout(180_000L) {
                // Media3 Transformer owns the application main Looper.
                withContext(Dispatchers.Main.immediate) {
                    VideoEditor(context).export(Uri.fromFile(fixture),
                        VideoEditSpec(
                            startMs = 0L,
                            endMs = 800L,
                            specialFx = VideoSpecialFxDraft(
                                VideoSpecialFxType.CINEMA_BARS, intensity = 1f),
                        ),
                    )
                }
            }

            val original = readFrame(fixture)
            val result = readFrame(requireNotNull(output))
            try {
                val origTop = original.getPixel(original.width / 2, original.height / 20)
                val bakedTop = result.getPixel(result.width / 2, result.height / 20)
                val bakedMiddle = result.getPixel(result.width / 2, result.height / 2)
                fun luminance(pixel: Int): Int =
                    (android.graphics.Color.red(pixel) +
                        android.graphics.Color.green(pixel) +
                        android.graphics.Color.blue(pixel)) / 3

                assertTrue("Source is white; otherwise pixel test is invalid",
                    luminance(origTop) > 180)
                assertTrue("Media3 must burn real black cinema bars into MP4",
                    luminance(bakedTop) < 85)
                assertTrue("Effect must not accidentally blank the whole video",
                    luminance(bakedMiddle) > 150)
            } finally {
                original.recycle()
                result.recycle()
            }
        } finally {
            fixture.delete()
            output?.delete()
        }
    }

    /** All catalog entries must change actual encoded pixels, not just thumbnails. */
    @Test fun allTenEffectsRespondToZeroHalfAndFullStrengthInMp4() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "fx-matrix-source.mp4")
        val encoded = instrumentation.context.assets.open("fx_white_h264_160x90_800ms.base64")
            .bufferedReader().use { it.readText().trim() }
        source.writeBytes(Base64.decode(encoded, Base64.DEFAULT))
        val evidence = File(context.getExternalFilesDir(null), "video-fx-evidence").apply { mkdirs() }
        val metrics = StringBuilder("effect,strength,mean_rgb_delta_from_zero\n")
        suspend fun exported(type: VideoSpecialFxType, intensity: Float): Bitmap {
            val output = withTimeout(180_000L) {
                withContext(Dispatchers.Main.immediate) {
                    VideoEditor(context).export(Uri.fromFile(source), VideoEditSpec(endMs = 800L,
                        specialFx = VideoSpecialFxDraft(type, intensity)))
                }
            }
            return try {
                output.copyTo(File(evidence, "${type.name}_${(intensity * 100).toInt()}.mp4"), overwrite = true)
                readFrame(output)
            } finally { output.delete() }
        }
        fun delta(a: Bitmap, b: Bitmap): Double {
            require(a.width == b.width && a.height == b.height)
            val left = IntArray(a.width * a.height)
            val right = IntArray(left.size)
            a.getPixels(left, 0, a.width, 0, 0, a.width, a.height)
            b.getPixels(right, 0, b.width, 0, 0, b.width, b.height)
            var sum = 0L
            for (i in left.indices) for (shift in listOf(0, 8, 16)) {
                sum += kotlin.math.abs(((left[i] ushr shift) and 255) - ((right[i] ushr shift) and 255))
            }
            return sum.toDouble() / (left.size * 3)
        }
        val zero = exported(VideoSpecialFxType.NONE, 0f)
        try {
            for (type in VideoSpecialFxType.entries.filterNot { it == VideoSpecialFxType.NONE }) {
                val neutral = exported(type, 0f)
                try { assertTrue("$type at 0% must preserve pixels", delta(zero, neutral) < 1.0) }
                finally { neutral.recycle() }
                val half = exported(type, .5f)
                val full = exported(type, 1f)
                try {
                    val halfChange = delta(zero, half)
                    val fullChange = delta(zero, full)
                    metrics.append("${type.name},50,$halfChange\n${type.name},100,$fullChange\n")
                    assertTrue("$type at 50% must change MP4 pixels ($halfChange)", halfChange > .04)
                    assertTrue("$type at 100% must change MP4 pixels ($fullChange)", fullChange > .04)
                    assertTrue("$type strength must affect encoded output", delta(half, full) > .04)
                } finally { half.recycle(); full.recycle() }
            }
        } finally {
            File(evidence, "metrics.csv").writeText(metrics.toString())
            zero.recycle()
            source.delete()
        }
    }

    private fun readFrame(file: File): Bitmap {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            requireNotNull(retriever.getFrameAtTime(
                300_000L, MediaMetadataRetriever.OPTION_CLOSEST)) {
                "No decoded frame in ${file.name}"
            }
        } finally {
            retriever.release()
        }
    }
}

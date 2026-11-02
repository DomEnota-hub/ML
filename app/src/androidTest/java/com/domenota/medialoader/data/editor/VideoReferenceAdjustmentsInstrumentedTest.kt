package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** One focused real-codec matrix for the thirteen newly implemented controls. */
@RunWith(AndroidJUnit4::class)
class VideoReferenceAdjustmentsInstrumentedTest {
    @Test fun allThirteenControlsChangeEncodedPixelsAndRespondToStrength() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "reference-adjustment-source.mp4")
        source.writeBytes(Base64.decode(instrumentation.context.assets.open("fx_colors_aac_h264_160x90_800ms.base64")
            .bufferedReader().use { it.readText() }, Base64.DEFAULT))
        val evidence = File(context.getExternalFilesDir(null), "video-fx-evidence").apply { mkdirs() }
        fun frame(file: File): Bitmap {
            val retriever = MediaMetadataRetriever()
            return try { retriever.setDataSource(file.absolutePath)
                requireNotNull(retriever.getFrameAtTime(300_000, MediaMetadataRetriever.OPTION_CLOSEST))
            } finally { retriever.release() }
        }
        fun difference(a: Bitmap, b: Bitmap): Double {
            require(a.width == b.width && a.height == b.height)
            var sum = 0L
            for (y in 0 until a.height) for (x in 0 until a.width) {
                val p = a.getPixel(x, y); val q = b.getPixel(x, y)
                for (shift in listOf(0, 8, 16)) sum += kotlin.math.abs(((p ushr shift) and 255) - ((q ushr shift) and 255))
            }
            return sum.toDouble() / (a.width * a.height * 3)
        }
        suspend fun export(settings: VideoReferenceAdjustments, name: String): Bitmap {
            val output = withTimeout(180_000) { withContext(Dispatchers.Main.immediate) {
                VideoEditor(context).export(Uri.fromFile(source), VideoEditSpec(endMs = 800,
                    color = VideoColorGrade(reference = settings)))
            } }
            return try { output.copyTo(File(evidence, "adjust_$name.mp4"), true); frame(output) }
                finally { output.delete() }
        }
        val metrics = StringBuilder("control,half_full_rgb_difference,photo_reference_error\n")
        val input = frame(source)
        try {
            for (control in VideoReferenceAdjustment.entries) {
                val sign = if (control.minimum < 0) -1f else 1f
                val half = export(VideoReferenceAdjustments().changed(control, sign * 50), "${control.name}_50")
                val settings = VideoReferenceAdjustments().changed(control, sign * 100)
                val full = export(settings, "${control.name}_100")
                try {
                    val change = difference(half, full)
                    assertTrue("$control strength has no real MP4 effect ($change)", change > .04)
                    var photoError = -1.0
                    if (control in listOf(VideoReferenceAdjustment.EXPOSURE, VideoReferenceAdjustment.HIGHLIGHTS,
                            VideoReferenceAdjustment.WARMTH)) {
                        val expected = PhotoColorMath.apply(input, settings.toPhoto())
                        try { photoError = difference(expected, full)
                            assertTrue("$control differs from shared photo algorithm: $photoError", photoError < 12)
                        } finally { if (expected !== input) expected.recycle() }
                    }
                    metrics.append("${control.name},$change,$photoError\n")
                } finally { half.recycle(); full.recycle() }
            }
        } finally {
            File(evidence, "adjustment-metrics.csv").writeText(metrics.toString())
            val automation = instrumentation.uiAutomation
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                "mkdir -p /sdcard/Download/ml-video-fx-evidence; cp -r ${evidence.absolutePath}/. /sdcard/Download/ml-video-fx-evidence/")).use { it.readBytes() }
            input.recycle(); source.delete()
        }
    }
}

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
    @Test fun projectAspectIsRealInMp4AndJpegAndKeepsTitlesOnCanvas() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "project-aspect-source.mp4")
        source.writeBytes(Base64.decode(instrumentation.context.assets.open("fx_trim_portrait_h264_90x160_20s.base64")
            .bufferedReader().use { it.readText() }, Base64.DEFAULT))
        val evidence = File(context.getExternalFilesDir(null), "video-fx-evidence").apply { mkdirs() }
        try {
            for (aspect in listOf(VideoProjectAspect.SQUARE, VideoProjectAspect.LANDSCAPE_16_9)) {
                val project = VideoProjectDraft(listOf(VideoClipDraft("clip", Uri.fromFile(source).toString(),
                    20_000, outMs = 800)), aspect = aspect)
                val spec = VideoEditSpec(project = project, textLayers = listOf(TextLayer(text = "A",
                    centerX = .08f, centerY = .5f, size = .2f, bold = true, color = android.graphics.Color.RED)))
                val output = withTimeout(180_000) { withContext(Dispatchers.Main.immediate) {
                    VideoEditor(context).export(Uri.fromFile(source), spec)
                } }
                val jpeg = VideoFrameExporter(context).export(Uri.fromFile(source), 300, spec)
                try {
                    val retriever = MediaMetadataRetriever()
                    val frame = try { retriever.setDataSource(output.absolutePath)
                        requireNotNull(retriever.getFrameAtTime(300_000, MediaMetadataRetriever.OPTION_CLOSEST))
                    } finally { retriever.release() }
                    val still = requireNotNull(android.graphics.BitmapFactory.decodeFile(jpeg.absolutePath))
                    try {
                        val expected = aspect.dimensions(VideoExportDimensions(90, 160))
                        for (image in listOf(frame, still)) {
                            assertTrue("$aspect: canvas size is wrong", image.width == expected.width && image.height == expected.height)
                            val corner = image.getPixel(0, 0)
                            assertTrue("$aspect: portrait source must fit with black side bars", android.graphics.Color.red(corner) < 25)
                            var redOnCanvas = 0
                            for (y in image.height / 4 until image.height * 3 / 4)
                                for (x in 0 until image.width / 5) {
                                    val pixel = image.getPixel(x, y)
                                    if (android.graphics.Color.red(pixel) > android.graphics.Color.green(pixel) + 70) redOnCanvas++
                                }
                            assertTrue("$aspect: title was incorrectly limited to the source image rectangle", redOnCanvas > 3)
                        }
                    } finally { frame.recycle(); still.recycle() }
                    output.copyTo(File(evidence, "aspect_${aspect.name}.mp4"), true)
                    jpeg.copyTo(File(evidence, "aspect_${aspect.name}.jpg"), true)
                } finally { output.delete(); jpeg.delete() }
            }
        } finally {
            for (command in listOf("mkdir -p /sdcard/Download/ml-video-fx-evidence",
                "cp -r ${evidence.absolutePath}/. /sdcard/Download/ml-video-fx-evidence/")) {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
            }
            source.delete()
        }
    }

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
            for (command in listOf("mkdir -p /sdcard/Download/ml-video-fx-evidence",
                "cp -r ${evidence.absolutePath}/. /sdcard/Download/ml-video-fx-evidence/")) {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
            }
            input.recycle(); source.delete()
        }
    }
}

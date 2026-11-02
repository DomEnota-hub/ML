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

    private fun readFrame(file: File): Bitmap {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            requireNotNull(retriever.getFrameAtTime(
                300_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)) {
                "No decoded frame in ${file.name}"
            }
        } finally {
            retriever.release()
        }
    }
}

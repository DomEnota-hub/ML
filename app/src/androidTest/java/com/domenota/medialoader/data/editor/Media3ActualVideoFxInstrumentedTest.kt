package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.test.core.app.ActivityScenario
import androidx.media3.common.MediaItem
import androidx.media3.common.VideoFrameProcessor
import com.domenota.medialoader.testing.VideoEffectsProbeActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
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
    @Test fun cinemaBarsAreActuallyBakedIntoMp4() = runBlocking<Unit> {
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
    @Test fun allTenEffectsRespondToZeroHalfAndFullStrengthInMp4() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "fx-matrix-source.mp4")
        val encoded = instrumentation.context.assets.open("fx_gray_h264_160x90_800ms.base64")
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
            preserveEvidence(evidence)
            zero.recycle()
            source.delete()
        }
    }

    /** Reads the real SurfaceView on Android: paused redraw and playing frames. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun effectsRedrawPausedFramesPlayAndMatchMp4() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "fx-player-source.mp4")
        source.writeBytes(Base64.decode(instrumentation.context.assets
            .open("fx_gray_h264_160x90_800ms.base64").bufferedReader().use { it.readText() }, Base64.DEFAULT))
        val evidence = File(context.getExternalFilesDir(null), "video-fx-evidence").apply { mkdirs() }
        val scenario = ActivityScenario.launch(VideoEffectsProbeActivity::class.java)
        suspend fun capture(): Bitmap? {
            val answer = CompletableDeferred<Int>()
            val image = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
            scenario.onActivity { activity ->
                PixelCopy.request(activity.videoSurface, image,
                    { status -> answer.complete(status) }, Handler(Looper.getMainLooper()))
            }
            return if (withTimeout(10_000L) { answer.await() } == PixelCopy.SUCCESS) image
                else { image.recycle(); null }
        }
        fun difference(a: Bitmap, b: Bitmap): Double {
            val p = IntArray(160 * 90); val q = IntArray(p.size)
            a.getPixels(p, 0, 160, 0, 0, 160, 90); b.getPixels(q, 0, 160, 0, 0, 160, 90)
            var total = 0L
            for (i in p.indices) for (shift in listOf(0, 8, 16))
                total += kotlin.math.abs(((p[i] ushr shift) and 255) - ((q[i] ushr shift) and 255))
            return total.toDouble() / (p.size * 3)
        }
        var previewStage = "initial frame"
        suspend fun awaitFrame(condition: (Bitmap) -> Boolean): Bitmap = try { withTimeout(20_000L) {
            var found: Bitmap? = null
            while (found == null) {
                delay(100)
                var playerError: androidx.media3.common.PlaybackException? = null
                var ready = false
                scenario.onActivity {
                    playerError = it.player.playerError
                    ready = it.player.playbackState == androidx.media3.common.Player.STATE_READY
                }
                if (playerError != null) throw AssertionError("$previewStage: player failed", playerError)
                if (!ready) continue
                val image = capture() ?: continue
                if (condition(image)) found = image else image.recycle()
            }
            requireNotNull(found)
        } } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            capture()?.let { image ->
                File(evidence, "timeout_${previewStage.replace(' ', '_')}.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
            throw AssertionError("No matching SurfaceView frame: $previewStage", timeout)
        }
        fun apply(type: VideoSpecialFxType, intensity: Float, clipIndex: Int = 0) {
            scenario.onActivity { activity ->
                activity.player.pause()
                activity.player.seekTo(clipIndex, 250L)
                activity.session.update(VideoEditSpec(specialFx = VideoSpecialFxDraft(type, intensity)))
            }
        }
        var baseline: Bitmap? = null
        try {
            scenario.onActivity { activity ->
                activity.player.setMediaItems(listOf(MediaItem.fromUri(Uri.fromFile(source)),
                    MediaItem.fromUri(Uri.fromFile(source))))
                activity.player.seekTo(0, 250L)
                activity.player.prepare()
            }
            val white = awaitFrame { image -> android.graphics.Color.red(image.getPixel(80, 45)) in 80..145 }
            baseline = white
            File(evidence, "preview_baseline.png").outputStream().use { white.compress(Bitmap.CompressFormat.PNG, 100, it) }
            for ((index, type) in VideoSpecialFxType.entries.filterNot { it == VideoSpecialFxType.NONE }.withIndex()) {
                previewStage = "$type zero"
                apply(type, 0f, index % 2)
                awaitFrame { difference(white, it) < .5 }.recycle()
                previewStage = "$type half paused"
                apply(type, .5f, index % 2)
                val half = awaitFrame { difference(white, it) > .04 }
                File(evidence, "${type.name}_half_paused.png").outputStream().use { half.compress(Bitmap.CompressFormat.PNG, 100, it) }
                previewStage = "$type full paused"
                apply(type, 1f, index % 2)
                val full = try { awaitFrame { difference(white, it) > .04 && difference(half, it) > .04 } }
                    finally { half.recycle() }
                var pausedFrameUs = 250_000L
                scenario.onActivity { activity ->
                    // Decoder metadata can already describe a prefetched next-clip buffer.
                    // The paused player's item-relative presentation position is the visible clock.
                    assertTrue("Paused preview must retain the selected clip", activity.player.currentMediaItemIndex == index % 2)
                    assertTrue("Paused preview moved away from the requested frame", kotlin.math.abs(activity.player.currentPosition - 250L) <= 1L)
                    pausedFrameUs = activity.player.currentPosition * 1000L
                }
                try {
                    File(evidence, "${type.name}_paused.png").outputStream().use {
                        full.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    previewStage = "$type playing"
                    scenario.onActivity { it.player.play() }
                    delay(200)
                    var playing = false
                    scenario.onActivity { playing = it.player.isPlaying }
                    assertTrue("$type must play through the GPU effect pipeline", playing)
                    awaitFrame { difference(white, it) > .04 }.recycle()
                    scenario.onActivity { it.player.pause() }
                    val output = withTimeout(180_000L) { withContext(Dispatchers.Main.immediate) {
                        VideoEditor(context).export(Uri.fromFile(source), VideoEditSpec(endMs = 800L,
                            specialFx = VideoSpecialFxDraft(type, 1f)))
                    } }
                    try {
                        // Compare the displayed item-relative paused position, including pulse phase.
                        assertTrue("Unexpected player frame timestamp: $pausedFrameUs",
                            pausedFrameUs in 0L..800_000L)
                        val encodedFrame = readFrame(output, pausedFrameUs)
                        try { assertTrue("$type: preview differs from MP4 at $pausedFrameUs us",
                            difference(full, encodedFrame) < 12.0) }
                        finally { encodedFrame.recycle() }
                    } finally { output.delete() }
                } finally { full.recycle() }
            }
            // The stable preview stage must execute real photo/LUT/RGB programs too,
            // including multiple stages with intermediate framebuffers, at unchanged PTS.
            val grades = listOf(
                "photo" to VideoColorGrade(photoEffect = PhotoEffect.WARM),
                "reference" to VideoColorGrade(reference = VideoReferenceAdjustments(exposure = -70f)),
                "combined" to VideoColorGrade(photoEffect = PhotoEffect.WARM,
                    manual = VideoColorAdjustments(brightness = -.08f, red = .8f),
                    reference = VideoReferenceAdjustments(warmth = -50f)))
            for ((label, grade) in grades) {
                previewStage = "$label neutral"
                scenario.onActivity {
                    it.player.pause(); it.player.seekTo(0, 250L)
                    it.session.update(VideoEditSpec())
                }
                awaitFrame { difference(white, it) < 3.0 }.recycle()
                previewStage = "$label colour paused"
                scenario.onActivity { it.session.update(VideoEditSpec(color = grade)) }
                val shown = awaitFrame { difference(white, it) > .5 }
                try {
                    val output = withTimeout(180_000L) { withContext(Dispatchers.Main.immediate) {
                        VideoEditor(context).export(Uri.fromFile(source), VideoEditSpec(endMs = 800L, color = grade))
                    } }
                    try {
                        val encoded = readFrame(output, 250_000L)
                        try { assertTrue("$label stable preview differs from real export",
                            difference(shown, encoded) < 12.0) } finally { encoded.recycle() }
                    } finally { output.delete() }
                    File(evidence, "preview_colour_$label.png").outputStream().use {
                        shown.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { shown.recycle() }
            }
            // A source trim shifts window time, but its renderer still supplies source-period
            // timestamps. Captions must use the same local interval as the exported clip.
            previewStage = "clipped timed title"
            val title = TextLayer(text = "A", size = .25f, color = android.graphics.Color.RED,
                outline = 0f, startMs = 200, endMs = 300)
            scenario.onActivity {
                it.player.pause()
                it.player.setMediaItem(MediaItem.Builder().setUri(Uri.fromFile(source))
                    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(200).setEndPositionMs(800).build()).build())
                it.player.seekTo(250L)
                it.session.update(VideoEditSpec(startMs = 200, textLayers = listOf(title)))
                it.player.prepare()
            }
            fun redTitle(image: Bitmap): Boolean {
                var count = 0
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    val c = image.getPixel(x, y)
                    if (android.graphics.Color.red(c) > 150 && android.graphics.Color.green(c) < 80) count++
                }
                return count > 3
            }
            awaitFrame(::redTitle).recycle()
            previewStage = "clipped title before interval"
            scenario.onActivity { it.player.seekTo(0L) }
            awaitFrame { !redTitle(it) && difference(white, it) < .5 }.recycle()
        } finally { preserveEvidence(evidence); baseline?.recycle(); scenario.close(); source.delete() }
    }

    /** These are native editor screenshots for HUMAN parity review, not a visual-golden pass. */
    @Test fun actualEditorOpensReferenceSectionsWithoutCrash() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "fx-ui-source.mp4")
        source.writeBytes(Base64.decode(instrumentation.context.assets
            .open("fx_trim_portrait_h264_90x160_20s.base64").bufferedReader().use { it.readText() }, Base64.DEFAULT))
        val evidence = File(context.getExternalFilesDir(null), "video-fx-evidence").apply { mkdirs() }
        val intent = android.content.Intent(context,
            com.domenota.medialoader.testing.VideoEditorUiProbeActivity::class.java)
            .setData(Uri.fromFile(source)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val scenario = ActivityScenario.launch<com.domenota.medialoader.testing.VideoEditorUiProbeActivity>(intent)
        fun node(label: String): android.view.accessibility.AccessibilityNodeInfo? {
            fun search(n: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                if (n.text?.toString()?.replace('\n', ' ') == label || n.contentDescription?.toString() == label) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { child -> search(child)?.let { return it } }
                return null
            }
            return instrumentation.uiAutomation.rootInActiveWindow?.let(::search)
        }
        suspend fun click(label: String) {
            withTimeout(10_000) { while (node(label) == null) delay(100) }
            var target = node(label)
            while (target != null && !target.isClickable) target = target.parent
            assertTrue("No clickable native editor control: $label", target?.performAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true)
            delay(700)
        }
        fun screenshot(name: String) {
            val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(evidence, "ui_$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        try {
            withTimeout(20_000) { while (node("Редактор") == null) delay(100) }
            delay(1500)
            screenshot("01_root_portrait")
            click("Редактор"); screenshot("02_editor")
            click("Обрезка"); screenshot("03_trim_source")
            click("Применить изменения")
            click("Соотношение сторон"); screenshot("04_aspect")
            click("Применить изменения")
            click("Откорректировать"); screenshot("05_adjustments_tone")
            click("Детализация"); screenshot("06_adjustments_detail")
            click("Отменить изменения раздела")
            click("Музыка"); screenshot("07_music_picker")
            click("Онлайн"); screenshot("08_music_online_state")
            click("Назад из музыки")
        } finally { preserveEvidence(evidence); scenario.close(); source.delete() }
    }

    @Test fun eightTextLayersAndTimedStickerAreBakedIntoMp4() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "fx-text-source.mp4")
        source.writeBytes(Base64.decode(instrumentation.context.assets
            .open("fx_gray_h264_160x90_800ms.base64").bufferedReader().use { it.readText() }, Base64.DEFAULT))
        val evidence = File(context.getExternalFilesDir(null), "video-fx-evidence").apply { mkdirs() }
        val layers = (0..7).map { i -> TextLayer(text = "A", centerX = .125f + (i % 4) * .25f,
            centerY = if (i < 4) .25f else .75f, size = .2f, bold = true, shadow = true,
            outline = .04f, startMs = if (i < 4) 0 else 400, endMs = if (i < 4) 400 else 800) }
        val sticker = VideoGraphicLayer(kind = VideoGraphicKind.STAR, size = .3f,
            color = android.graphics.Color.RED, startMs = 400, endMs = 800)
        try {
            val output = withTimeout(180_000) { withContext(Dispatchers.Main.immediate) {
                VideoEditor(context).export(Uri.fromFile(source), VideoEditSpec(endMs = 800,
                    textLayers = layers, graphicLayers = listOf(sticker)))
            } }
            try {
                output.copyTo(File(evidence, "text_8_layers_timed_sticker.mp4"), overwrite = true)
                for ((time, activeTop) in listOf(200_000L to true, 600_000L to false)) {
                    val frame = readFrame(output, time)
                    try {
                        fun whitePixels(top: Boolean, column: Int): Int {
                            val x0 = column * frame.width / 4
                            val y0 = if (top) 0 else frame.height * 2 / 3
                            val y1 = if (top) frame.height / 3 else frame.height
                            var count = 0
                            for (y in y0 until y1) for (x in x0 until (column + 1) * frame.width / 4) {
                                val c = frame.getPixel(x, y)
                                if (android.graphics.Color.red(c) > 190 && android.graphics.Color.green(c) > 190 &&
                                    android.graphics.Color.blue(c) > 190) count++
                            }
                            return count
                        }
                        for (column in 0..3) {
                            assertTrue("Text layer absent at $time, column=$column", whitePixels(activeTop, column) > 3)
                            assertTrue("Text interval leaked at $time, column=$column", whitePixels(!activeTop, column) == 0)
                        }
                        val c = frame.getPixel(frame.width / 2, frame.height / 2)
                        assertTrue("Timed sticker visibility at $time", if (activeTop)
                            kotlin.math.abs(android.graphics.Color.red(c) - android.graphics.Color.green(c)) < 20
                            else android.graphics.Color.red(c) > android.graphics.Color.green(c) + 60)
                    } finally { frame.recycle() }
                }
            } finally { output.delete() }
        } finally { preserveEvidence(evidence); source.delete() }
    }

    @Test fun trimExportsTwentyFifteenAndRestoredTwentySecondsWithoutChangingSource() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "fx-trim-source.mp4")
        source.writeBytes(Base64.decode(instrumentation.context.assets
            .open("fx_trim_portrait_h264_90x160_20s.base64").bufferedReader().use { it.readText() }, Base64.DEFAULT))
        val originalHash = java.security.MessageDigest.getInstance("SHA-256").digest(source.readBytes())
        val evidence = File(context.getExternalFilesDir(null), "video-fx-evidence").apply { mkdirs() }
        val original = VideoProjectDraft(listOf(VideoClipDraft("trim", Uri.fromFile(source).toString(), 20_000)))
        val history = VideoEditHistory<VideoProjectDraft>()
        history.record(original)
        val right = original.trimClip("trim", 0, 15_000)
        history.record(right)
        assertTrue(history.undo() == original && history.redo() == right)
        val left = original.trimClip("trim", 5_000, 20_000)
        val restored = left.trimClip("trim", 0, 20_000)
        assertTrue(restored == original)
        val rows = StringBuilder("case,duration_ms,expected_ms,first_frame_mean_error,last_frame_mean_error\n")
        fun difference(a: Bitmap, b: Bitmap): Double {
            require(a.width == b.width && a.height == b.height)
            var sum = 0L
            for (y in 0 until a.height) for (x in 0 until a.width) {
                val p = a.getPixel(x, y); val q = b.getPixel(x, y)
                for (shift in listOf(0, 8, 16)) sum += kotlin.math.abs(((p ushr shift) and 255) - ((q ushr shift) and 255))
            }
            return sum.toDouble() / (a.width * a.height * 3)
        }
        try {
            for ((label, project) in listOf("right_15" to right, "left_15" to left, "restored_20" to restored)) {
                val output = withTimeout(180_000) { withContext(Dispatchers.Main.immediate) {
                    VideoEditor(context).export(Uri.fromFile(source), VideoEditSpec(project = project))
                } }
                try {
                    val retriever = MediaMetadataRetriever()
                    val duration = try { retriever.setDataSource(output.absolutePath)
                        requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
                    } finally { retriever.release() }
                    assertTrue("$label: wrong encoded duration $duration", kotlin.math.abs(duration - project.durationMs) <= 110)
                    val clip = project.clips.single()
                    val first = readFrame(output, 0); val firstSource = readFrame(source, clip.inMs * 1000)
                    val last = readFrame(output, (project.durationMs - 100) * 1000)
                    val lastSource = readFrame(source, (clip.outMs - 100) * 1000)
                    try {
                        val firstError = difference(first, firstSource); val lastError = difference(last, lastSource)
                        rows.append("$label,$duration,${project.durationMs},$firstError,$lastError\n")
                        assertTrue("$label: wrong first source frame, error=$firstError", firstError < 12)
                        assertTrue("$label: wrong last source frame, error=$lastError", lastError < 12)
                    } finally { first.recycle(); firstSource.recycle(); last.recycle(); lastSource.recycle() }
                    output.copyTo(File(evidence, "trim_$label.mp4"), overwrite = true)
                } finally { output.delete() }
            }
            assertTrue("Non-destructive trim altered the source", originalHash.contentEquals(
                java.security.MessageDigest.getInstance("SHA-256").digest(source.readBytes())))
        } finally { File(evidence, "trim-metrics.csv").writeText(rows.toString()); preserveEvidence(evidence); source.delete() }
    }

    @Test fun jpegFrameIncludesSecondClipEffectsAndTimedText() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "fx-jpeg-source.mp4")
        source.writeBytes(Base64.decode(instrumentation.context.assets
            .open("fx_gray_h264_160x90_800ms.base64").bufferedReader().use { it.readText() }, Base64.DEFAULT))
        val evidence = File(context.getExternalFilesDir(null), "video-fx-evidence").apply { mkdirs() }
        val sourceUri = Uri.fromFile(source)
        val project = VideoProjectDraft(listOf(VideoClipDraft("first", sourceUri.toString(), 800),
            VideoClipDraft("second", sourceUri.toString(), 800,
                specialFx = VideoSpecialFxDraft(VideoSpecialFxType.CINEMA_BARS, 1f))))
        val text = TextLayer(text = "A", color = android.graphics.Color.RED, bold = true,
            size = .25f, outline = 0f, startMs = 800, endMs = 1600)
        var jpeg: File? = null
        try {
            jpeg = VideoFrameExporter(context).export(sourceUri, 1100, VideoEditSpec(project = project,
                textLayers = listOf(text)))
            val image = requireNotNull(android.graphics.BitmapFactory.decodeFile(jpeg.absolutePath))
            try {
                val top = image.getPixel(image.width / 2, image.height / 20)
                assertTrue("JPEG lost selected second clip's cinema bars", android.graphics.Color.red(top) < 70)
                var red = 0
                for (y in image.height / 3 until image.height * 2 / 3)
                    for (x in image.width / 3 until image.width * 2 / 3) {
                        val c = image.getPixel(x, y)
                        if (android.graphics.Color.red(c) > android.graphics.Color.green(c) + 70) red++
                    }
                assertTrue("JPEG lost project-timed text from the second clip", red > 5)
                assertTrue("JPEG must preserve source dimensions", image.width == 160 && image.height == 90)
            } finally { image.recycle() }
            jpeg.copyTo(File(evidence, "current_second_clip.jpg"), true)
        } finally { preserveEvidence(evidence); jpeg?.delete(); source.delete() }
    }

    @Test fun cancellingActualTransformerRemovesPartialOutputAndAllowsNextExport() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "fx-cancel-source.mp4")
        source.writeBytes(Base64.decode(instrumentation.context.assets
            .open("fx_trim_portrait_h264_90x160_20s.base64").bufferedReader().use { it.readText() }, Base64.DEFAULT))
        val existing = context.cacheDir.listFiles().orEmpty().map { it.name }.toSet()
        fun partials() = context.cacheDir.listFiles().orEmpty().filter {
            it.name.startsWith("video-edit-") && it.name.endsWith(".mp4") && it.name !in existing }
        val progress = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val job = kotlinx.coroutines.CoroutineScope(coroutineContext).launch(Dispatchers.Main.immediate) {
            VideoEditor(context).export(Uri.fromFile(source), VideoEditSpec(endMs = 20_000,
                specialFx = VideoSpecialFxDraft(VideoSpecialFxType.FILM_GRAIN, 1f),
                exportSettings = VideoExportSettings(resolution = VideoExportResolution.FULL_HD_1080))) {
                progress += it
            }.delete()
        }
        try {
            withTimeout(20_000) {
                while (partials().none { it.length() > 0 }) {
                    assertTrue("Export ended before cancellation could be exercised", job.isActive)
                    delay(25)
                }
            }
            job.cancel()
            job.join()
            withTimeout(10_000) { while (partials().isNotEmpty()) delay(50) }
            assertTrue("Cancelled job must not report completion", 100 !in progress)
            val next = withTimeout(180_000) { withContext(Dispatchers.Main.immediate) {
                VideoEditor(context).export(Uri.fromFile(source), VideoEditSpec(endMs = 500,
                    specialFx = VideoSpecialFxDraft(VideoSpecialFxType.CINEMA_BARS, 1f)))
            } }
            try { readFrame(next, 0).recycle() } finally { next.delete() }
        } finally { job.cancel(); job.join(); source.delete() }
    }

    @Test fun sharedPhotoCatalogMatchesPhotoRendererInMp4() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "fx-photo-catalog-source.mp4")
        source.writeBytes(Base64.decode(instrumentation.context.assets
            .open("fx_colors_aac_h264_160x90_800ms.base64").bufferedReader().use { it.readText() }, Base64.DEFAULT))
        val input = readFrame(source)
        val evidence = File(context.getExternalFilesDir(null), "video-fx-evidence").apply { mkdirs() }
        val metrics = StringBuilder("photo_filter,strength,mean_rgb_error\n")
        try {
            for (preset in PhotoEffect.entries.filterNot { it == PhotoEffect.NONE }) {
                val levels = if (preset in listOf(PhotoEffect.MONO, PhotoEffect.WARM,
                    PhotoEffect.FILM_FADED, PhotoEffect.TEXTURE_GRAIN)) listOf(.5f, 1f) else listOf(1f)
                for (strength in levels) {
                    val settings = PhotoColorAdjustments(effect = preset, effectStrength = strength * 100f)
                    val matrix = PhotoColorMath.apply(input, settings)
                    val expected = matrix // PhotoColorMath.apply already includes fade/grain post-processing.
                    var output: File? = null
                    try {
                        output = withTimeout(180_000L) { withContext(Dispatchers.Main.immediate) {
                            VideoEditor(context).export(Uri.fromFile(source), VideoEditSpec(endMs = 800L,
                                color = VideoColorGrade(photoEffect = preset, intensity = strength)))
                        } }
                        output.copyTo(File(evidence, "photo_${preset.name}_${(strength * 100).toInt()}.mp4"), true)
                        val actual = readFrame(output)
                        try {
                            val a = IntArray(actual.width * actual.height)
                            val e = IntArray(expected.width * expected.height)
                            assertTrue("Photo filter changed output geometry", a.size == e.size)
                            actual.getPixels(a, 0, actual.width, 0, 0, actual.width, actual.height)
                            expected.getPixels(e, 0, expected.width, 0, 0, expected.width, expected.height)
                            var error = 0L
                            for (i in a.indices) for (shift in listOf(0, 8, 16))
                                error += kotlin.math.abs(((a[i] ushr shift) and 255) - ((e[i] ushr shift) and 255))
                            val meanError = error.toDouble() / (a.size * 3)
                            metrics.append("${preset.name},$strength,$meanError\n")
                            assertTrue("$preset: MP4 differs from photo pipeline, mean error=$meanError",
                                meanError < 10.0)
                        } finally { actual.recycle() }
                    } finally {
                        output?.delete()
                        if (expected !== matrix && expected !== input) expected.recycle()
                        if (matrix !== input) matrix.recycle()
                    }
                }
            }
        } finally {
            File(evidence, "photo-filter-metrics.csv").writeText(metrics.toString())
            preserveEvidence(evidence); input.recycle(); source.delete()
        }
    }

    @Test fun detachedSpeedAndExternalMp3ReallyMixToAacWithoutDoubleSound() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val work = File(context.cacheDir, "fx-audio-evidence").apply { mkdirs() }
        val evidence = File(context.getExternalFilesDir(null), "video-fx-evidence").apply { mkdirs() }
        val source = File(work, "source.mp4")
        source.writeBytes(Base64.decode(instrumentation.context.assets
            .open("fx_colors_aac_h264_160x90_800ms.base64").bufferedReader().use { it.readText() }, Base64.DEFAULT))
        suspend fun ffmpeg(args: List<String>) = withContext(Dispatchers.IO) {
            val session = com.arthenica.ffmpegkit.FFmpegKit.executeWithArguments(args.toTypedArray())
            assertTrue("FFmpeg audio-tier failed: ${session.allLogsAsString.takeLast(1500)}",
                com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode))
        }
        var detachedOutput: File? = null
        var mp3Output: File? = null
        var extracted: File? = null
        try {
            assertTrue(VideoOriginalAudioDetach.hasAudio(context, Uri.fromFile(source).toString()))
            val clip = VideoClipDraft("audio-speed", Uri.fromFile(source).toString(), 800, speed = 2f)
            val draft = VideoOriginalAudioDetach.detach(VideoProjectDraft(listOf(clip)), setOf(clip.id)).project
            assertTrue(draft.audioTracks.single().speed == 2f && draft.durationMs == 400L)
            detachedOutput = withTimeout(180_000) { withContext(Dispatchers.Main.immediate) {
                VideoEditor(context).export(Uri.fromFile(source), VideoEditSpec(project = draft, removeAudio = true))
            } }
            val mp3 = File(work, "external.mp3")
            ffmpeg(listOf("-y", "-i", source.absolutePath, "-vn", "-c:a", "libmp3lame", "-b:a", "64k", mp3.absolutePath))
            assertTrue("MP3 encoder must produce a real file", mp3.length() > 1000)
            val track = draft.audioTracks.single().copy(sourceUri = Uri.fromFile(mp3).toString(),
                volume = .35f, fadeInMs = 80, fadeOutMs = 80)
            mp3Output = withTimeout(180_000) { withContext(Dispatchers.Main.immediate) {
                VideoEditor(context).export(Uri.fromFile(source), VideoEditSpec(
                    project = draft.copy(audioTracks = listOf(track)), removeAudio = true))
            } }
            extracted = withTimeout(180_000) { withContext(Dispatchers.Main.immediate) {
                VideoEditor(context).extractAudio(Uri.fromFile(mp3Output), 0, 400)
            } }
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(extracted.absolutePath)
                assertTrue("M4A contains only AAC", extractor.trackCount == 1 &&
                    extractor.getTrackFormat(0).getString(android.media.MediaFormat.KEY_MIME) == "audio/mp4a-latm")
            } finally { extractor.release() }
            val pcm = File(work, "decoded.pcm")
            ffmpeg(listOf("-y", "-i", extracted.absolutePath, "-f", "s16le", "-ac", "1", "-ar", "16000", pcm.absolutePath))
            val bytes = pcm.readBytes()
            val samples = ShortArray(bytes.size / 2) { i ->
                ((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
            }
            fun rms(from: Int, to: Int): Double = kotlin.math.sqrt(
                (from until to.coerceAtMost(samples.size)).map { val s = samples[it] / 32768.0; s * s }.average())
            assertTrue("AAC duration must follow the sped 400ms project", samples.size in 5600..8000)
            val middle = rms(3200, 4480)
            assertTrue("Sound missing or doubled, RMS=$middle", middle in .015.. .045)
            assertTrue("Fade-in must reach the real waveform", rms(0, 640) < middle * .8)
            var rising = 0
            for (i in 3201 until 4480) if (samples[i - 1] <= 0 && samples[i] > 0) rising++
            val hz = rising * 16000.0 / (4480 - 3200)
            assertTrue("atempo must preserve the 440Hz pitch, got $hz", hz in 400.0..480.0)
            detachedOutput.copyTo(File(evidence, "detached_2x.mp4"), true)
            mp3Output.copyTo(File(evidence, "mp3_mix_2x_fade.mp4"), true)
            extracted.copyTo(File(evidence, "extracted_audio.m4a"), true)
            File(evidence, "audio-metrics.txt").writeText("samples=${samples.size} rms=$middle hz=$hz")
        } finally {
            preserveEvidence(evidence); detachedOutput?.delete(); mp3Output?.delete(); extracted?.delete()
            work.deleteRecursively()
        }
    }

    private fun preserveEvidence(directory: File) {
        // Gradle uninstalls test APKs after connected tests; copy before uninstall.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand(command)).use { it.readBytes() }
        }
        shell("mkdir -p /sdcard/Download/ml-video-fx-evidence")
        shell("cp -r ${directory.absolutePath}/. /sdcard/Download/ml-video-fx-evidence/")
    }

    private fun readFrame(file: File, timeUs: Long = 300_000L): Bitmap {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            requireNotNull(retriever.getFrameAtTime(
                timeUs, MediaMetadataRetriever.OPTION_CLOSEST)) {
                "No decoded frame in ${file.name}"
            }
        } finally {
            retriever.release()
        }
    }
}

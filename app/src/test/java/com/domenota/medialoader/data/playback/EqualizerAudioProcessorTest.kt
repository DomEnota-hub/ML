package com.domenota.medialoader.data.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class EqualizerAudioProcessorTest {
    @Test fun releaseBeforeAnyTrackWasConfiguredIsSafe() { EqualizerAudioProcessor().reset() }
    private fun process(settings: EqualizerSettings, frequency: Double = 910.0, rate: Int = 48000,
        channels: Int = 1, encoding: Int = C.ENCODING_PCM_FLOAT): Pair<FloatArray, FloatArray> {
        val processor = EqualizerAudioProcessor()
        processor.updateSettings(settings)
        processor.configure(AudioFormat(rate, channels, encoding)); processor.flush()
        val values = FloatArray(rate / 2 * channels) { index ->
            if (index % channels == 0) (.1 * sin(2 * PI * frequency * (index / channels) / rate)).toFloat() else 0f
        }
        val bytes = ByteBuffer.allocateDirect(values.size * if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2).order(ByteOrder.nativeOrder())
        values.forEach { if (encoding == C.ENCODING_PCM_FLOAT) bytes.putFloat(it) else bytes.putShort((it * 32767).toInt().toShort()) }
        bytes.flip(); processor.queueInput(bytes)
        val result = processor.output.order(ByteOrder.nativeOrder())
        val output = FloatArray(values.size) { if (encoding == C.ENCODING_PCM_FLOAT) result.float else result.short / 32768f }
        assertFalse(result.hasRemaining()); assertFalse(bytes.hasRemaining())
        return values to output
    }
    private fun rms(values: FloatArray): Double = sqrt(values.drop(values.size / 2).map { it.toDouble().pow(2) }.average())

    @Test fun disabledAndFlatAreExactFloatBypass() {
        listOf(EqualizerSettings(false, listOf(12f, -12f, 8f, 1f, 2f), "Свой"), EqualizerSettings()).forEach {
            val (input, output) = process(it, channels = 2)
            assertArrayEquals(input, output, 0f)
        }
    }
    @Test fun cutAttenuatesTargetAndBoostChangesSpectralBalance() {
        val cut = EqualizerSettings(true, listOf(0f, 0f, -12f, 0f, 0f), "Свой")
        assertTrue(rms(process(cut).second) < rms(process(EqualizerSettings()).second) * .35)
        val boost = cut.copy(gainsDb = listOf(0f, 0f, 12f, 0f, 0f))
        assertTrue(rms(process(boost).second) > rms(process(boost, frequency = 80.0).second) * 2)
    }
    @Test fun pcm16KeepsFramesAndDoesNotLeakAcrossChannels() {
        val (_, output) = process(EqualizerSettings(true, listOf(0f, 0f, 9f, 0f, 0f), "Свой"), channels = 2, encoding = C.ENCODING_PCM_16BIT)
        assertTrue(output.all { it.isFinite() && abs(it) <= 1f })
        assertTrue(output.filterIndexed { index, _ -> index % 2 == 1 }.all { it == 0f })
        assertTrue(rms(output) > 0)
    }
    @Test fun unavailableBandsAreBypassedAndFlushClearsHistory() {
        val (_, lowRate) = process(EqualizerSettings(true, listOf(0f, 0f, 0f, 0f, 12f), "Свой"), rate = 8000)
        val (reference, _) = process(EqualizerSettings(), rate = 8000)
        assertArrayEquals(reference, lowRate, 0f)
        val p = EqualizerAudioProcessor()
        p.updateSettings(EqualizerSettings(true, listOf(12f, 12f, 12f, 12f, 12f), "Свой"))
        p.configure(AudioFormat(48000, 1, C.ENCODING_PCM_FLOAT)); p.flush()
        p.queueInput(ByteBuffer.allocateDirect(400).order(ByteOrder.nativeOrder()).apply { repeat(100) { putFloat(1f) }; flip() }); p.output
        p.configure(AudioFormat(8000, 2, C.ENCODING_PCM_FLOAT)); p.flush()
        p.queueInput(ByteBuffer.allocateDirect(800).order(ByteOrder.nativeOrder()))
        val output = p.output.order(ByteOrder.nativeOrder())
        while (output.hasRemaining()) assertEquals(0f, output.float, 0f)
    }
    @Test fun settingsRejectNonFiniteAndClampGains() {
        assertTrue(runCatching { EqualizerSettings(true, listOf(Float.NaN, 0f, 0f, 0f, 0f), "Свой") }.isFailure)
        assertTrue(runCatching { EqualizerSettings(true, listOf(0f), "Свой") }.isFailure)
        assertEquals(12f, EqualizerSettings().withBand(0, 90f).gainsDb[0])
        assertEquals("Свой", EqualizerSettings().withBand(0, 2f).preset)
    }
    @Test fun strongInputAndLiveUpdatesStayFiniteWithoutChangingFrameCount() {
        val p = EqualizerAudioProcessor()
        p.configure(AudioFormat(44100, 2, C.ENCODING_PCM_FLOAT)); p.flush()
        repeat(20) { block ->
            p.updateSettings(EqualizerSettings(block % 3 != 0, List(5) { if (block % 2 == 0) 12f else -12f }, "Свой"))
            val input = ByteBuffer.allocateDirect(4096).order(ByteOrder.nativeOrder())
            repeat(1024) { input.putFloat(if (it % 7 == 0) 4f else -1f) }; input.flip()
            p.queueInput(input)
            val output = p.output.order(ByteOrder.nativeOrder())
            assertEquals(4096, output.remaining())
            while (output.hasRemaining()) {
                val value = output.float
                assertTrue(value.isFinite())
                // Disabled bypass preserves PCM exactly; enabled filters always bound output.
                if (block != 0) assertTrue(abs(value) <= 1f)
            }
        }
    }
}

package com.domenota.medialoader.data.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class MusicLoudnessTest {
    private fun measure(amplitude: Double, stereo: Boolean = false, silentTail: Int = 0): MusicLoudnessMeasurement {
        val meter = MusicLoudnessMeter(48000, if (stereo) 2 else 1)
        repeat(96000) { i ->
            val sample = amplitude * sin(2 * PI * 1000 * i / 48000)
            meter.accept(sample, -sample)
        }
        repeat(48000 * silentTail) { meter.accept(0.0, 0.0) }
        return requireNotNull(meter.finish())
    }
    @Test fun gatedStereoLoudnessTracksLevelWithoutPhaseCancellation() {
        val mono = measure(.1)
        val stereo = measure(.1, true)
        assertEquals(3.0103, stereo.lufs - mono.lufs, .02)
        assertEquals(6.0206, measure(.2).lufs - mono.lufs, .02)
        assertEquals(.1, mono.samplePeak, .00001)
        assertEquals(-23.0, mono.lufs, .4)
    }
    @Test fun silenceAndShortAudioRemainUnmeasured() {
        val silence = MusicLoudnessMeter(48000, 2)
        repeat(48000) { silence.accept(0.0) }
        assertNull(silence.finish())
        val short = MusicLoudnessMeter(48000, 1)
        repeat(100) { short.accept(.1) }
        assertNull(short.finish())
    }
    @Test fun absoluteAndRelativeGatesExcludeSilentTail() {
        assertEquals(measure(.2).lufs, measure(.2, silentTail = 10).lufs, 1.0)
        assertTrue(measure(.2).lufs > measure(.02).lufs)
    }
    @Test fun gainTargetsPerceivedLevelWithSamplePeakAndBoostLimit() {
        assertEquals(1.0, MusicLoudnessPolicy.gain(null), 0.0)
        assertEquals(1.0, MusicLoudnessPolicy.gain(MusicLoudnessMeasurement(Double.NaN,.5)), 0.0)
        assertEquals(10.0.pow(-6.0/20), MusicLoudnessPolicy.gain(MusicLoudnessMeasurement(-12.0,.5)), .0001)
        assertEquals(10.0.pow(6.0/20), MusicLoudnessPolicy.gain(MusicLoudnessMeasurement(-40.0,.1)), .0001)
        assertEquals(10.0.pow(-1.0/20), MusicLoudnessPolicy.gain(MusicLoudnessMeasurement(-40.0,1.0)), .0001)
    }
    private fun pcm(gain: Double, eq: EqualizerSettings, sample: Float = .25f): Float {
        val processor = EqualizerAudioProcessor(publishFormat = false)
        processor.updateSettings(eq); processor.updateNormalizationGain(gain)
        processor.configure(AudioFormat(48000,1,C.ENCODING_PCM_FLOAT)); processor.flush()
        val input = ByteBuffer.allocateDirect(48000 * 4).order(ByteOrder.nativeOrder())
        repeat(48000) { input.putFloat(sample) }; input.flip()
        processor.queueInput(input)
        val out = processor.output.order(ByteOrder.nativeOrder())
        assertEquals(48000*4, out.remaining())
        var last = 0f
        while (out.hasRemaining()) { last = out.float; assertTrue(last.isFinite() && abs(last) <= 1f) }
        return last
    }
    @Test fun normalizationChangesPcmEvenWithEqOff() {
        assertEquals(.125f, pcm(.5, EqualizerSettings(enabled=false)), .0001f)
        assertEquals(.25f, pcm(1.0, EqualizerSettings(enabled=false)), 0f)
    }
    @Test fun independentDeckGainsAndEqStayBounded() {
        val eq = EqualizerSettings(true,listOf(6f,3f,0f,0f,0f),"Свой")
        assertEquals(pcm(1.0,eq)*.5f,pcm(.5,eq),.001f)
        assertTrue(pcm(2.0,EqualizerSettings(),.9f) <= 1f)
        val a = pcm(.5,EqualizerSettings())
        val b = pcm(1.5,EqualizerSettings())
        val (out,incoming) = CrossfadePolicy.gains(2500,5000)
        assertEquals(.25f, a*out+b*incoming,.0001f)
    }
}

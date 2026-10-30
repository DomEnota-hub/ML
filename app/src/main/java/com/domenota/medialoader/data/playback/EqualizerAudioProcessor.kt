package com.domenota.medialoader.data.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object EqualizerPlaybackFormat {
    private val mutable = MutableStateFlow<Int?>(null)
    val sampleRate = mutable.asStateFlow()
    fun update(rate: Int?) { mutable.value = rate }
}

/** PCM-only peaking EQ. All filter memory belongs to the audio thread, snapshots are immutable.
 * Gain changes approach their targets over ~20ms. Positive gains reserve aggregate headroom;
 * the final clamp is a guard for malformed/overshooting inputs, never a wrapping integer cast.
 */
@OptIn(UnstableApi::class)
class EqualizerAudioProcessor : BaseAudioProcessor() {
    @Volatile private var target = EqualizerSettings()
    private var rate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID
    private var gains = DoubleArray(5)
    private var desired = DoubleArray(5)
    private var filters = Array(5) { Filter(0) }
    private var headroom = 1.0
    private var frame = 0
    fun updateSettings(settings: EqualizerSettings) { target = settings.copy(gainsDb = settings.gainsDb.toList()) }

    override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (input.encoding != C.ENCODING_PCM_16BIT && input.encoding != C.ENCODING_PCM_FLOAT)
            throw AudioProcessor.UnhandledAudioFormatException(input)
        return input // Remain active even at zero gains so live changes require no player restart.
    }
    override fun onFlush() {
        rate = inputAudioFormat.sampleRate; channels = inputAudioFormat.channelCount; encoding = inputAudioFormat.encoding
        EqualizerPlaybackFormat.update(rate)
        filters = Array(5) { Filter(channels) }
        gains.fill(0.0); desired.fill(0.0); headroom = 1.0; frame = 0
    }
    override fun onReset() { rate = 0; channels = 0; filters = Array(5) { Filter(0) }; gains.fill(0.0); EqualizerPlaybackFormat.update(null) }
    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        inputBuffer.order(ByteOrder.nativeOrder())
        val output = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.nativeOrder())
        val snapshot = target
        for (band in 0..4) desired[band] = if (snapshot.enabled && EqualizerSettings.FREQUENCIES[band] < rate / 2.0)
            snapshot.gainsDb[band].toDouble() else 0.0
        if (desired.all { it == 0.0 } && gains.all { it == 0.0 }) {
            output.put(inputBuffer); output.flip(); return
        }
        val step = 1.0 - exp(-32.0 / (rate * .020))
        while (inputBuffer.hasRemaining()) {
            if (frame++ % 32 == 0) {
                for (band in 0..4) {
                    gains[band] += (desired[band] - gains[band]) * step
                    if (abs(gains[band] - desired[band]) < .00001) gains[band] = desired[band]
                    filters[band].configure(EqualizerSettings.FREQUENCIES[band].toDouble(), rate, gains[band])
                }
                headroom = 10.0.pow(-gains.sumOf { max(0.0, it) } / 20.0)
            }
            for (channel in 0 until channels) {
                var value = if (encoding == C.ENCODING_PCM_FLOAT) inputBuffer.float.toDouble() else inputBuffer.short / 32768.0
                if (!value.isFinite()) value = 0.0
                value *= headroom
                for (filter in filters) value = filter.sample(value, channel)
                value = if (value.isFinite()) value.coerceIn(-1.0, 1.0) else 0.0
                if (encoding == C.ENCODING_PCM_FLOAT) output.putFloat(value.toFloat())
                else output.putShort((value * 32768).roundToInt().coerceIn(-32768, 32767).toShort())
            }
        }
        output.flip()
    }

    private class Filter(channels: Int) {
        private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
        private var a1 = 0.0; private var a2 = 0.0
        private val z1 = DoubleArray(channels); private val z2 = DoubleArray(channels)
        fun configure(frequency: Double, rate: Int, gain: Double) {
            if (frequency >= rate / 2.0 || abs(gain) < .000001) {
                b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0
                z1.fill(0.0); z2.fill(0.0); return
            }
            val amplitude = 10.0.pow(gain / 40.0)
            val omega = 2 * PI * frequency / rate
            val alpha = sin(omega) / (2 * 1.0) // Q = 1, stable peaking biquads.
            val denominator = 1 + alpha / amplitude
            b0 = (1 + alpha * amplitude) / denominator; b1 = -2 * cos(omega) / denominator
            b2 = (1 - alpha * amplitude) / denominator; a1 = b1; a2 = (1 - alpha / amplitude) / denominator
        }
        fun sample(input: Double, channel: Int): Double {
            val output = b0 * input + z1[channel]
            z1[channel] = b1 * input - a1 * output + z2[channel]
            z2[channel] = b2 * input - a2 * output
            return output
        }
    }
}

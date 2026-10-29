package com.domenota.medialoader.data.playback

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.*

/** Keep music decoded to PCM so changing EQ in-flight never needs a session/queue restart.
 * Video players have their own default renderers and are unaffected. Encoded passthrough and
 * hardware offload cannot process these bands, so neither is advertised by this music sink.
 */
internal class EqualizerRenderersFactory(context: Context, private val processor: EqualizerAudioProcessor) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
        val sink = DefaultAudioSink.Builder(context).setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(processor)).build()
        return object : ForwardingAudioSink(sink) {
            override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
            override fun getFormatSupport(format: Format): Int =
                if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED
            override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = AudioOffloadSupport.DEFAULT_UNSUPPORTED
        }
    }
}

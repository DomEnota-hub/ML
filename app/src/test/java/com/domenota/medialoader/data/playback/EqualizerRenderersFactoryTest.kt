package com.domenota.medialoader.data.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EqualizerRenderersFactoryTest {
    @Test fun sinkForcesDecodedPcmAndNeverAdvertisesOffload() {
        val context: Context = RuntimeEnvironment.getApplication()
        val factory = EqualizerRenderersFactory(context, EqualizerAudioProcessor())
        val method = EqualizerRenderersFactory::class.java.getDeclaredMethod("buildAudioSink", Context::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        val sink = method.invoke(factory, context, false, false) as AudioSink
        val pcm = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setSampleRate(48000).setChannelCount(2).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
        val encoded = pcm.buildUpon().setSampleMimeType(MimeTypes.AUDIO_AC3).build()
        assertTrue(sink.supportsFormat(pcm))
        assertFalse(sink.supportsFormat(encoded))
        assertFalse(sink.getFormatOffloadSupport(encoded).isFormatSupported)
        sink.release()
    }
}

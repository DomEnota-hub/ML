package com.domenota.medialoader.data.playback

import com.domenota.medialoader.data.playback.alignment.PcmResamplingWindow
import org.junit.Assert.*
import org.junit.Test

class LyricsResamplingWindowTest {
    @Test fun coreWindowsPreserveAbsoluteSamplesOddTailAndBoundedInputAtEveryOriginalRate() {
        for(rate in listOf(8000,11025,22050,24000,32000,44100,48000)) {
            val result=ArrayList<Float>();var largest=0
            val stream=PcmResamplingWindow(rate,{input->
                largest=maxOf(largest,input.size)
                // An ideal resampler of an absolute-index ramp is analytically known.
                FloatArray(((input.size.toLong()*16000+rate-1)/rate).toInt()){i->input[0]+(i.toDouble()*rate/16000.0).toFloat()}
            },{result.add(it)},{})
            val count=rate*3+17
            repeat(count){stream.accept(it.toFloat())};stream.finish();stream.finish()
            assertEquals(((count.toLong()*16000+rate-1)/rate).toInt(),result.size)
            result.forEachIndexed{i,value->assertEquals(i.toDouble()*rate/16000.0,value.toDouble(),.02)}
            assertTrue(largest<=rate+2*(rate/25))
        }
    }
    @Test fun cancellationAndMalformedFilterOutputCannotBeSilentlyAccepted() {
        val cancelled=PcmResamplingWindow(8000,{error("must cancel before engine")},{error("must not emit")},{throw IllegalStateException("cancel")})
        assertTrue(runCatching{repeat(8320){cancelled.accept(0f)}}.isFailure)
        val malformed=PcmResamplingWindow(48000,{floatArrayOf()},{error("must not emit")},{})
        malformed.accept(1f);assertTrue(runCatching{malformed.finish()}.isFailure)
        assertTrue(runCatching{PcmResamplingWindow(48000,{it},{},{ }).accept(Float.NaN)}.isFailure)
    }
}

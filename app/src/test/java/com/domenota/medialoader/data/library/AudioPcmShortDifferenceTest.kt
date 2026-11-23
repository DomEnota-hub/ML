package com.domenota.medialoader.data.library

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class AudioPcmShortDifferenceTest {
    private fun audio(): FloatArray = FloatArray(4096) { i ->
        (0.45 * sin(2 * PI * 440 * i / 8000.0) +
         0.27 * sin(2 * PI * 1007 * i / 8000.0) +
         0.15 * sin(2 * PI * (160 + i / 97) * i / 8000.0)).toFloat()
    }
    @Test fun twoConsecutiveChangedFramesAreVisibleButNotOneCodecImpulse() {
        val a = audio(); val b = a.copyOf()
        for (i in 1024 + 550 until 1024 + 1450) b[i] *= .23f
        assertTrue(AudioPcmShortDifference.confirms(a,b))
        val blip = a.copyOf()
        for (i in 1024 + 530 until 1024 + 580) blip[i] *= .1f
        assertFalse(AudioPcmShortDifference.confirms(a,blip))
    }
    @Test fun identicalGainAndSmallCodecTimeShiftAreNotEdits() {
        val a = audio()
        assertFalse(AudioPcmShortDifference.confirms(a,a))
        assertFalse(AudioPcmShortDifference.confirms(a,FloatArray(a.size){i->a[i]*.68f}))
        val shifted = FloatArray(a.size) { i -> a[(i + 99) % a.size] * .9f }
        assertFalse(AudioPcmShortDifference.confirms(a,shifted))
    }
    @Test fun unrelatedAndNearSilentRecordingsFailClosed() {
        val a=audio()
        val unrelated=FloatArray(a.size){i-> (.4*sin(2*PI*797*i/8000.0)).toFloat()}
        assertFalse(AudioPcmShortDifference.confirms(a,unrelated))
        assertFalse(AudioPcmShortDifference.confirms(FloatArray(4096){.00001f},FloatArray(4096){.00002f}))
        assertFalse(AudioPcmShortDifference.confirms(a,FloatArray(50)))
    }
}

package com.domenota.medialoader.ui.screens.music
import org.junit.Assert.*
import org.junit.Test
class MusicFastScrollPolicyTest {
    @Test fun mappingClampsAndReachesEnds() {
        assertEquals(0,MusicFastScrollPolicy.index(-1f,100))
        assertEquals(0,MusicFastScrollPolicy.index(.5f,0))
        assertEquals(0,MusicFastScrollPolicy.index(.5f,1))
        assertEquals(99,MusicFastScrollPolicy.index(1.3f,100))
        assertEquals(50,MusicFastScrollPolicy.index(.5f,101))
        assertEquals(1f,MusicFastScrollPolicy.fraction(100,101),.0001f)
        assertEquals(0f,MusicFastScrollPolicy.fraction(-2,101),.0001f)
    }
}

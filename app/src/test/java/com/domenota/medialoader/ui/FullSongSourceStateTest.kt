package com.domenota.medialoader.ui

import com.domenota.medialoader.data.search.FullSongSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullSongSourceStateTest {
    @Test fun sourceRemainsFrozenThroughRecognitionUpdates() {
        val initial = PreviewState().copy(
            fullSongOpen = true, fullSongBusy = true, fullSongSessionId = 7,
            fullSongSource = FullSongSource.MUZMO,
        )
        val recognized = initial.copy(
            fullSongBusy = false, fullSongArtist = "Ева Власова",
            fullSongTitle = "Вчера",
        )
        assertTrue(recognized.fullSongOpen)
        assertEquals(7L, recognized.fullSongSessionId)
        assertEquals(FullSongSource.MUZMO, recognized.fullSongSource)
        assertEquals(FullSongSource.HITMOZ, PreviewState().fullSongSource)
    }
}

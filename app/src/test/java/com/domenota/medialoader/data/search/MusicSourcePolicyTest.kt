package com.domenota.medialoader.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicSourcePolicyTest {
    @Test fun switchingToMuzmoCancelsOnlyQueuedHitMozWork() {
        assertEquals(
            "hitmoz",
            MusicSourcePolicy.queuedProviderToCancel(FullSongSource.HITMOZ, FullSongSource.MUZMO),
        )
    }

    @Test fun retainingSourceDoesNotCancelAnything() {
        assertNull(MusicSourcePolicy.queuedProviderToCancel(FullSongSource.MUZMO, FullSongSource.MUZMO))
    }

    @Test fun automaticModeDoesNotCancelQueuedDownloadsWhenChangingModes() {
        assertNull(MusicSourcePolicy.queuedProviderToCancel(FullSongSource.HITMOZ, FullSongSource.AUTO))
        assertNull(MusicSourcePolicy.queuedProviderToCancel(FullSongSource.AUTO, FullSongSource.MUZMO))
    }
}

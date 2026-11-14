package com.domenota.medialoader.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTrackDescriptionTest {
    @Test fun absentMetadataUsesStableFallbackWithoutGuessedCodecOrChannels() {
        assertEquals("Дорожка 3", audioTrackDescription(null, "und", null, -1, 3))
        assertEquals("Дорожка 1", audioTrackDescription(" ", null, "", 0, 1))
    }
    @Test fun suppliedMetadataIsKeptAndRepeatedTitleIsOmitted() {
        assertEquals("Commentary · en · mp4a.40.2 · 2 кан.",
            audioTrackDescription("Commentary", "en", "mp4a.40.2", 2, 1))
        assertEquals("en · 6 кан.", audioTrackDescription("en", "en", null, 6, 2))
    }
}

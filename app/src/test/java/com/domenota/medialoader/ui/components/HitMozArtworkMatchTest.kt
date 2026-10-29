package com.domenota.medialoader.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HitMozArtworkMatchTest {
    @Test fun exactArtistAndTitleReuseRecognizedArtwork() {
        assertTrue(matchesRecognizedMusic("Jahballa, Ibu DaDon", "Active", "Jahballa Ibu DaDon", "ACTIVE"))
        assertTrue(matchesRecognizedMusic("Tones and I", "Dance Monkey", "Tones & I", "Dance Monkey"))
    }

    @Test fun remixOrDifferentArtistDoNotReuseArtwork() {
        assertFalse(matchesRecognizedMusic("Jahballa, Ibu DaDon", "Active", "Jahballa, Ibu DaDon", "Active (Remix)"))
        assertFalse(matchesRecognizedMusic("Tones and I", "Dance Monkey", "Other Artist", "Dance Monkey"))
        assertFalse(matchesRecognizedMusic("", "Active", "Jahballa", "Active"))
    }
}

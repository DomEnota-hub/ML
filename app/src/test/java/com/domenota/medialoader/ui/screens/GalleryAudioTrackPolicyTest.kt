package com.domenota.medialoader.ui.screens

import org.junit.Assert.*
import org.junit.Test

class GalleryAudioTrackPolicyTest {
    @Test fun silenceUnknownSingleAndUnsupportedAlternativesDoNotShowSelector() {
        for (groups in listOf(emptyList(), listOf(emptyList()), listOf(listOf(true)),
            listOf(listOf(true, false)), listOf(listOf(false), listOf(false)))) {
            assertFalse(GalleryAudioTrackPolicy.showSelector(GalleryAudioTrackPolicy.availableCount(groups)))
        }
    }
    @Test fun twoSupportedTracksWithinOrAcrossGroupsShowSelector() {
        assertEquals(2, GalleryAudioTrackPolicy.availableCount(listOf(listOf(true, false, true))))
        assertTrue(GalleryAudioTrackPolicy.showSelector(
            GalleryAudioTrackPolicy.availableCount(listOf(listOf(true), listOf(true)))))
    }
    @Test fun switchingFromMultipleTracksToUnknownOrSingleHidesSelectorAgain() {
        val counts = listOf(listOf(listOf(true, true)), emptyList(), listOf(listOf(true)))
            .map(GalleryAudioTrackPolicy::availableCount)
        assertEquals(listOf(true, false, false), counts.map(GalleryAudioTrackPolicy::showSelector))
    }
}

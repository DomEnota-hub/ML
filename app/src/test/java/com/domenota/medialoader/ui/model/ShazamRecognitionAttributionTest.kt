package com.domenota.medialoader.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ShazamRecognitionAttributionTest {
    @Test fun noDotBetweenRecognizedTrackAndShazam() {
        val label = formatRecognitionAttribution(
            "Markul", "2 минуты", "Shazam", "SHAZAM_DIRECT"
        )
        assertEquals("Markul — 2 минуты Shazam", label)
        assertFalse(label.contains("минуты ·"))
    }

    @Test fun loopSourceKeepsItsOwnMeaningfulLabelWithoutTrackSeparator() {
        assertEquals(
            "Автор — Песня Shazam · loop",
            formatRecognitionAttribution("Автор", "Песня", "Shazam · loop", "SHAZAM_LOOP")
        )
    }

    @Test fun punctuationInsideRealTrackTitleIsNotRemoved() {
        assertEquals(
            "Автор — Точка. После названия Shazam",
            formatRecognitionAttribution("Автор", "Точка. После названия", "Shazam", "SHAZAM_DIRECT")
        )
    }

    @Test fun unrelatedMetadataKeepsItsOriginalAttributionSeparator() {
        assertEquals(
            "Исполнитель — Песня · Instagram",
            formatRecognitionAttribution("Исполнитель", "Песня", "Instagram", "SOURCE_METADATA")
        )
    }

    @Test fun sourceAloneIsShownWhenRecognitionHasNoTitle() {
        assertEquals("Shazam", formatRecognitionAttribution(null, null, "Shazam", "SHAZAM_DIRECT"))
    }
}

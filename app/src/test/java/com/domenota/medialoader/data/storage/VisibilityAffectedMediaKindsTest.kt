package com.domenota.medialoader.data.storage

import org.junit.Assert.*
import org.junit.Test

class VisibilityAffectedMediaKindsTest {
    private val visual = setOf(HistoricalMediaKind.VISUAL)
    private val audio = setOf(HistoricalMediaKind.AUDIO)
    private val both = HistoricalMediaKind.entries.toSet()

    @Test fun visualOnlyRestoresDoNotForceUnrelatedMusicProviderScan() {
        assertEquals(visual,visibilityAffectedMediaKinds(listOf("image/jpeg")))
        assertEquals(visual,visibilityAffectedMediaKinds(listOf("video/mp4","image/png")))
    }

    @Test fun audioOnlyRestoresDoNotForceUnrelatedGalleryProviderScan() {
        assertEquals(audio,visibilityAffectedMediaKinds(listOf("audio/mpeg","audio/flac")))
    }

    @Test fun successiveConflatedVisualThenAudioOperationsRefreshBoth() {
        assertEquals(both,aggregateRecoveryMediaImpacts(
            10,12,mapOf(11L to visual,12L to audio)))
        assertEquals(visual,aggregateRecoveryMediaImpacts(
            10,12,mapOf(11L to visual,12L to visual)))
    }

    @Test fun missingOrAgedOutGenerationAlwaysRefreshesBoth() {
        assertEquals(both,aggregateRecoveryMediaImpacts(
            10,12,mapOf(12L to audio)))
        assertEquals(both,aggregateRecoveryMediaImpacts(
            10,45,mapOf(45L to visual)))
    }

    @Test fun mixedLegacyAndUnknownPlansFailClosedToBothKinds() {
        assertEquals(both,visibilityAffectedMediaKinds(listOf("audio/mpeg","video/mp4")))
        assertEquals(both,visibilityAffectedMediaKinds(emptyList()))
        assertEquals(both,visibilityAffectedMediaKinds(listOf("image/jpeg","application/octet-stream")))
        assertEquals(both,visibilityAffectedMediaKinds(listOf("")))
    }
}

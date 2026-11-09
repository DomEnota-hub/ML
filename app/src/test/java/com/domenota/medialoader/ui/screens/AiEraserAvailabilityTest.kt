package com.domenota.medialoader.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiEraserAvailabilityTest {
    @Test fun peopleModeUsesRealDetectorWithFailClosedInstallationChecks() {
        assertTrue(AiEraserMode.PEOPLE.available)
        assertNull(AiEraserMode.PEOPLE.unavailableExplanation)
        // UI launches PersonSegmentationPipeline only after both optional models verify.
        // A failed detector install must NOT silently reuse point-only MobileSAM as a detector.
    }

    @Test fun glareIsRealLocalSrnetAndReflectionStaysDisabled() {
        assertTrue(AiEraserMode.GLARE.available)
        assertNull(AiEraserMode.GLARE.unavailableExplanation)
        assertFalse(AiEraserMode.REFLECTION.available)
        assertTrue(requireNotNull(AiEraserMode.REFLECTION.unavailableExplanation)
            .contains("пока недоступно"))
    }

    @Test fun ExistingRealModesRemainSelectableWithoutFalseWarnings() {
        for (mode in listOf(AiEraserMode.OBJECTS, AiEraserMode.MANUAL, AiEraserMode.TEXT)) {
            assertTrue(mode.available)
            assertNull(mode.unavailableExplanation)
        }
    }
}

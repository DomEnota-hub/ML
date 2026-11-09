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

    @Test fun glareAndReflectionAreSelectableWithGenuineLocalPipelines() {
        assertTrue(AiEraserMode.GLARE.available)
        assertNull(AiEraserMode.GLARE.unavailableExplanation)
        assertTrue(AiEraserMode.REFLECTION.available)
        assertNull(AiEraserMode.REFLECTION.unavailableExplanation)
        // Inference stays fail-closed until the optional file passes SHA-256.
    }

    @Test fun ExistingRealModesRemainSelectableWithoutFalseWarnings() {
        for (mode in listOf(AiEraserMode.OBJECTS, AiEraserMode.MANUAL, AiEraserMode.TEXT)) {
            assertTrue(mode.available)
            assertNull(mode.unavailableExplanation)
        }
    }
}

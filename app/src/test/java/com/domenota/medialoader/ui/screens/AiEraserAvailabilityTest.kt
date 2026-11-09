package com.domenota.medialoader.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiEraserAvailabilityTest {
    @Test fun peopleCannotBeActivatedByMobileSamAlone() {
        assertFalse(AiEraserMode.PEOPLE.available)
        val reason = requireNotNull(AiEraserMode.PEOPLE.unavailableExplanation)
        assertTrue(reason.contains("MobileSAM"))
        assertTrue(reason.contains("не находит всех людей"))
        assertTrue(reason.contains("отдельные маски"))
        assertTrue(reason.contains("LaMa"))
    }

    @Test fun glareAndReflectionAreNotMisrepresentedAsReady() {
        assertFalse(AiEraserMode.GLARE.available)
        assertFalse(AiEraserMode.REFLECTION.available)
        assertTrue(requireNotNull(AiEraserMode.GLARE.unavailableExplanation).contains("пока недоступно"))
        assertTrue(requireNotNull(AiEraserMode.REFLECTION.unavailableExplanation).contains("пока недоступно"))
    }

    @Test fun ExistingRealModesRemainSelectableWithoutFalseWarnings() {
        for (mode in listOf(AiEraserMode.OBJECTS, AiEraserMode.MANUAL, AiEraserMode.TEXT)) {
            assertTrue(mode.available)
            assertNull(mode.unavailableExplanation)
        }
    }
}

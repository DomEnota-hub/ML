package com.domenota.medialoader.ui.screens

import com.domenota.medialoader.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage-1 policy: do not pretend the MobileSAM/specialist engines already exist. */
class AiEraserModePolicyTest {
    @Test fun nativeUiKeepsAllSixApprovedModeLabels() {
        assertEquals(listOf(
            "Удаление объектов", "Удаление людей", "Убрать блики",
            "Удалить отражение", "Вручную", "Удалить текст",
        ), AiEraserMode.entries.map { it.title })
    }

    @Test fun onlyWorkingLocalEnginesAreEnabled() {
        assertEquals(setOf(AiEraserMode.OBJECTS, AiEraserMode.MANUAL, AiEraserMode.TEXT),
            AiEraserMode.entries.filter { it.available }.toSet())
        assertFalse(AiEraserMode.PEOPLE.available)
        assertFalse(AiEraserMode.GLARE.available)
        assertFalse(AiEraserMode.REFLECTION.available)
    }

    @Test fun manualIconIsDistinctFromToggleHandButComesFromSameAssetFamily() {
        assertEquals(R.drawable.ic_ai_eraser_manual, AiEraserMode.MANUAL.icon)
        assertTrue(AiEraserMode.OBJECTS.available)
    }
}

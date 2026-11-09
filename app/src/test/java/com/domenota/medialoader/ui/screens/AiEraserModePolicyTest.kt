package com.domenota.medialoader.ui.screens

import com.domenota.medialoader.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage-2: people require a real optional detector + MobileSAM; glare/reflection remain unavailable. */
class AiEraserModePolicyTest {
    @Test fun nativeUiKeepsAllSixApprovedModeLabels() {
        assertEquals(listOf(
            "Удаление объектов", "Удаление людей", "Убрать блики",
            "Удалить отражение", "Вручную", "Удалить текст",
        ), AiEraserMode.entries.map { it.title })
    }

    @Test fun onlyWorkingLocalEnginesAreEnabled() {
        val expected = AiEraserMode.entries.toSet()
        assertEquals(expected, AiEraserMode.entries.filter { it.available }.toSet())
        assertTrue(AiEraserMode.PEOPLE.available)
        assertTrue(AiEraserMode.GLARE.available)
        assertTrue(AiEraserMode.REFLECTION.available)
    }

    @Test fun manualIconIsDistinctFromToggleHandButComesFromSameAssetFamily() {
        assertEquals(R.drawable.ic_ai_eraser_manual, AiEraserMode.MANUAL.icon)
        assertTrue(AiEraserMode.OBJECTS.available)
    }
}

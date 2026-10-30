package com.domenota.medialoader.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class DeleteConfirmationTest {
    @Test fun selectionCountUsesRussianFormsWithoutFileNames() {
        assertEquals("Удалить выбранный объект?", deleteSelectionTitle(1))
        assertEquals("Удалить выбранные 2 объекта?", deleteSelectionTitle(2))
        assertEquals("Удалить выбранные 5 объектов?", deleteSelectionTitle(5))
        assertEquals("Удалить выбранные 11 объектов?", deleteSelectionTitle(11))
        assertEquals("Удалить выбранный 21 объект?", deleteSelectionTitle(21))
    }
}

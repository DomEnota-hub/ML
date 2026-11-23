package com.domenota.medialoader.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class DuplicateIssueCopyTest {
    @Test fun privacyChangeIsAnActionableWarningNotGlobalFailure() {
        val copy=duplicateIssueCopy("Поиск сброшен после переноса в Личное")
        assertEquals("Результаты требуют проверки",copy.title)
        assertEquals(true,copy.message.contains("Проверьте результаты"))
    }

    @Test fun generalSearchErrorRetainsTechnicalMeaning() {
        val copy=duplicateIssueCopy("Не удалось загрузить результат")
        assertEquals("Не удалось завершить поиск",copy.title)
        assertEquals("Не удалось загрузить результат",copy.message)
    }
}

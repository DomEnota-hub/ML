package com.domenota.medialoader.ui.screens.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

/** Guard approved v10 photo/video menu order from unrequested UX rewrites. */
class GalleryV10ActionContractTest {
    @Test fun photoActionsKeepExactReferenceOrder() {
        assertEquals(listOf(
            "В альбом",
            "Установить как обои",
            "Сведения",
            "Извлечь",
            "Вырезка фото",
            "Изменить",
            "Защитный водяной знак",
            "Транслировать",
            "Копировать",
            "Открыть другим приложением",
            "Переместить в личное",
        ), galleryV10ActionLabels(isPhoto = true))
    }

    @Test fun videoActionsKeepExactReferenceOrder() {
        assertEquals(listOf(
            "В альбом",
            "Установить как обои",
            "Сведения",
            "Транслировать",
            "Зациклить одно видео",
            "Скорость воспроизведения",
            "Найти трек",
            "Копировать",
            "Открыть другим приложением",
            "Переместить в личное",
        ), galleryV10ActionLabels(isPhoto = false))
    }
}

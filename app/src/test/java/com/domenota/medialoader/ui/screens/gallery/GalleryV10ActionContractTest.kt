package com.domenota.medialoader.ui.screens.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

/** Keep photo menu unchanged and protect approved unified video menu accessibility. */
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
            "Пропорции видео",
            "Звуковые дорожки",
            "Перейти ко времени",
            "Найти трек",
            "Копировать",
            "Открыть другим приложением",
            "Переместить в личное",
        ), galleryV10ActionLabels(isPhoto = false))
    }
}

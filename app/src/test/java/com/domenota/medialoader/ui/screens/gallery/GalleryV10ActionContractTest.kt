package com.domenota.medialoader.ui.screens.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

/** Preserve approved action order and hide notes when the viewer cannot address them. */
class GalleryV10ActionContractTest {
    @Test fun photoActionsKeepExactReferenceOrder() {
        assertEquals(listOf(
            "В альбом",
            "Установить как обои",
            "Сведения",
            "Заметки",
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
            "Заметки",
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
    @Test fun notesAreAbsentWhenViewerHasNoPublicNoteAccess() {
        for (photo in listOf(true,false)) {
            assertEquals(galleryV10ActionLabels(photo).filterNot { it == "Заметки" },
                galleryV10ActionLabels(photo,allowNotes=false))
        }
    }
}

package com.domenota.medialoader.ui.screens

import com.domenota.medialoader.ui.model.DownloadUiItem
import com.domenota.medialoader.ui.model.DownloadUiState
import com.domenota.medialoader.ui.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorFilePickerTest {
    private fun item(id: String, name: String, date: String, kind: MediaKind = MediaKind.IMAGE) =
        DownloadUiItem(id = id, title = name, subtitle = "Файл", sizeLabel = null,
            state = DownloadUiState.COMPLETED, savedUri = "content://media/item/$id",
            dateLabel = date, kind = kind)

    private val files = listOf(
        item("a", "Дерево.jpg", "03.02.2026 10:00"),
        item("b", "Море.jpg", "12.11.2025 21:30"),
        item("c", "Альбом.jpg", "18.07.2026 12:14"),
        item("d", "Песня.mp3", "01.06.2026 11:00", MediaKind.AUDIO),
    )
    private val groups = mapOf(
        "a" to listOf("Камера"),
        "b" to listOf("Путешествия", "Избранное"),
        "c" to listOf("Избранное"),
    )

    @Test fun newestAndOldestUseRealDateNotLexicographicRussianDate() {
        assertEquals(listOf("c", "a", "b"), editorPickerChoices(
            files, MediaKind.IMAGE, "", null, groups, EditorPickerSort.NEWEST).map { it.id })
        assertEquals(listOf("b", "a", "c"), editorPickerChoices(
            files, MediaKind.IMAGE, "", null, groups, EditorPickerSort.OLDEST).map { it.id })
    }

    @Test fun albumFilterKeepsMultipleAlbumMembershipsWithoutDeletingMedia() {
        val result = editorPickerChoices(
            files, MediaKind.IMAGE, "", "Избранное", groups, EditorPickerSort.NAME_ASC)
        assertEquals(listOf("c", "b"), result.map { it.id })
        assertEquals(4, files.size)
    }

    @Test fun searchAndKindAreAppliedBeforeSorting() {
        val result = editorPickerChoices(files, MediaKind.IMAGE, "моРЕ", null, groups,
            EditorPickerSort.NAME_DESC)
        assertEquals(listOf("b"), result.map { it.id })
        assertTrue(editorPickerChoices(files, MediaKind.VIDEO, "", null, groups,
            EditorPickerSort.NEWEST).isEmpty())
    }

    @Test fun filesWithoutAlbumsAppearInUngroupedCategory() {
        val result = editorPickerChoices(files, MediaKind.AUDIO, "",
            "Без альбома", groups, EditorPickerSort.NEWEST)
        assertEquals(listOf("d"), result.map { it.id })
    }
}

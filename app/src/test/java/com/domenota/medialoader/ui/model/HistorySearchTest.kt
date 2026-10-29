package com.domenota.medialoader.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySearchTest {
    private fun item(id: String, title: String, artist: String? = null, track: String? = null) =
        DownloadUiItem(
            id = id, title = title, subtitle = "",
            sizeLabel = null, state = DownloadUiState.COMPLETED,
            recognizedArtist = artist, recognizedTitle = track,
        )

    @Test fun searchesFileNamesWithoutCaseSensitivity() {
        val item = item("1", "Не понимаю, зачем кричать.mp4")
        assertTrue(HistorySearch.matches(item, "КРИЧАТЬ"))
        assertTrue(HistorySearch.matches(item, "  не понимаю "))
    }

    @Test fun searchesRecognizedMusicInformation() {
        val song = item("1", "Instagram.mp3", artist = "Little Big", track = "Hypnodancer")
        assertTrue(HistorySearch.matches(song, "little"))
        assertTrue(HistorySearch.matches(song, "DANCER"))
    }

    @Test fun searchesInsideCollapsedPublicationGroupsAndKeepsOrder() {
        val first = item("a", "Отпуск_1.jpg")
        val second = item("b", "Не понимаю, зачем кричать_2.mp4")
        val third = item("c", "Скриншот.png")
        val result = HistorySearch.filterGroups(listOf(listOf(first, second), listOf(third)), "кричать")
        assertEquals(listOf(listOf(second)), result)
        assertEquals(listOf(listOf(first, second), listOf(third)),
            HistorySearch.filterGroups(listOf(listOf(first, second), listOf(third)), ""))
        assertTrue(HistorySearch.filterGroups(listOf(listOf(first)), "нет такой песни").isEmpty())
    }
}

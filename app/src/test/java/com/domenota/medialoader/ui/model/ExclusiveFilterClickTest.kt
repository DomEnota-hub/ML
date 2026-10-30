package com.domenota.medialoader.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExclusiveFilterClickTest {
    private val sources = setOf("Instagram", "YouTube", "VK", "RUTUBE")

    @Test fun clickingOneSourceFromAllShowsOnlyThatSource() {
        assertEquals(setOf("VK"), exclusiveFilterClick(sources, sources, "VK"))
    }

    @Test fun clickingSelectedSourceAgainRestoresAllSources() {
        assertEquals(sources, exclusiveFilterClick(sources, setOf("VK"), "VK"))
    }

    @Test fun switchingSelectedSourceDoesNotInvertFilter() {
        assertEquals(setOf("YouTube"), exclusiveFilterClick(sources, setOf("VK"), "YouTube"))
    }

    @Test fun alsoNormalizesLegacyMultiSourceSelection() {
        assertEquals(setOf("Instagram"), exclusiveFilterClick(sources, setOf("VK", "RUTUBE"), "Instagram"))
    }

    @Test fun refusesUnknownFilter() {
        assertThrows(IllegalArgumentException::class.java) {
            exclusiveFilterClick(sources, sources, "unknown")
        }
    }

    @Test fun russianTrashCountUsesCorrectDeclension() {
        val expected = mapOf(0 to "0 файлов", 1 to "1 файл", 2 to "2 файла",
            4 to "4 файла", 5 to "5 файлов", 11 to "11 файлов",
            14 to "14 файлов", 21 to "21 файл", 22 to "22 файла",
            25 to "25 файлов", 101 to "101 файл", 111 to "111 файлов")
        for ((count, label) in expected) assertEquals(label, russianFileCount(count))
    }
}

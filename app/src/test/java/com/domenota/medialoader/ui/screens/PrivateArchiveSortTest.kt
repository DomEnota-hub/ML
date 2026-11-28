package com.domenota.medialoader.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrivateArchiveSortTest {
    private val rows = listOf(
        PrivateArchiveSortKey("b", "Яблоко", 10, 90, 20, 3),
        PrivateArchiveSortKey("a", "Альбом", 30, 10, 10, 1),
        PrivateArchiveSortKey("c", "Видеофайл", null, null, null, 2),
    )
    private fun ids(field: PrivateArchiveSortField, ascending: Boolean) =
        sortPrivateArchive(rows, PrivateArchiveSort(field, ascending)) { it }.map { it.id }

    @Test fun nameAndCountRespectBothDirections() {
        assertEquals(listOf("a", "c", "b"), ids(PrivateArchiveSortField.NAME, true))
        assertEquals(listOf("b", "c", "a"), ids(PrivateArchiveSortField.NAME, false))
        assertEquals(listOf("a", "c", "b"), ids(PrivateArchiveSortField.COUNT, true))
        assertEquals(listOf("b", "c", "a"), ids(PrivateArchiveSortField.COUNT, false))
    }

    @Test fun unknownMetadataStaysLastInBothDirections() {
        assertEquals(listOf("b", "a", "c"), ids(PrivateArchiveSortField.FILE_DATE, true))
        assertEquals(listOf("a", "b", "c"), ids(PrivateArchiveSortField.FILE_DATE, false))
        assertEquals(listOf("a", "b", "c"), ids(PrivateArchiveSortField.ADDED, true))
        assertEquals(listOf("b", "a", "c"), ids(PrivateArchiveSortField.ADDED, false))
        assertEquals(listOf("a", "b", "c"), ids(PrivateArchiveSortField.SIZE, true))
        assertEquals(listOf("b", "a", "c"), ids(PrivateArchiveSortField.SIZE, false))
    }

    @Test fun groupsNeverInventMissingSizeOrOverflow() {
        val known = groupPrivateArchiveKey("g", "Группа", rows.take(2))
        assertEquals(2, known.count)
        assertEquals(30L, known.bytes)
        assertEquals(30L, known.fileDate)
        assertEquals(90L, known.added)
        assertNull(groupPrivateArchiveKey("g", "Группа", rows).bytes)
        assertNull(groupPrivateArchiveKey("g", "Группа", listOf(
            rows[0].copy(bytes = Long.MAX_VALUE), rows[1]
        )).bytes)
    }

    @Test fun equalValuesUseStableIdentityWithoutMutatingInput() {
        val same = rows.map { it.copy(name = "Файл") }
        assertEquals(listOf("b", "a", "c"),
            sortPrivateArchive(same, PrivateArchiveSort(PrivateArchiveSortField.NAME, false)) { it }.map { it.id })
        assertEquals(listOf("b", "a", "c"), same.map { it.id })
    }
}

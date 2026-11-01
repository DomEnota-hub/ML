package com.domenota.medialoader.ui.screens.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryRangeSelectionTest {
    private val ordered = listOf("a", "b", "c", "d", "e", "f")

    @Test fun longPressAndDragSelectFullRangeAcrossRows() {
        assertEquals(setOf("b", "c", "d", "e"),
            galleryRangeSelection(emptySet(), ordered, "b", "e"))
    }

    @Test fun reversingGestureShrinksOnlyNewRangeAndPreservesExistingChoice() {
        val previous = setOf("a")
        val first = galleryRangeSelection(previous, ordered, "b", "f")
        assertEquals(setOf("a", "b", "c", "d", "e", "f"), first)
        assertEquals(setOf("a", "b", "c"),
            galleryRangeSelection(previous, ordered, "b", "c"))
    }

    @Test fun invisibleFileIdsCannotBeChosen() {
        assertEquals(setOf("a", "b", "c"),
            galleryRangeSelection(setOf("a", "hidden"), ordered, "b", "c"))
        assertEquals(setOf("a"),
            galleryRangeSelection(setOf("a", "hidden"), ordered, "missing", "b"))
    }

    @Test fun dayToggleOnlyAffectsItsCurrentVisibleRows() {
        val day = listOf("a", "b", "c")
        val existing = setOf("b", "d")
        val selected = toggleGalleryDaySelection(existing, ordered, day)
        assertEquals(setOf("a", "b", "c", "d"), selected)
        assertEquals(setOf("d"), toggleGalleryDaySelection(selected, ordered, day))
    }

    @Test fun partiallySelectedDayAddsOnlyVisibleFiles() {
        assertEquals(setOf("a", "d"),
            toggleGalleryDaySelection(setOf("a", "hidden"), listOf("a", "d"), listOf("a", "b", "d")))
    }

    @Test fun hugeDaySelectionDoesNotAlterOtherDays() {
        val day = (0 until 1100).map { "day:$it" }
        val selected = toggleGalleryDaySelection(setOf("other"), day + "other", day)
        assertEquals(1101, selected.size)
        assertEquals(setOf("other"), toggleGalleryDaySelection(selected, day + "other", day))
    }
}

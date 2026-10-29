package com.domenota.medialoader.ui.model

import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class BatchFileNamesTest {
    private val selected = listOf(
        "first" to "First.jpg",
        "third" to "Third.png",
        "fourth" to "Fourth.jpg",
    )

    @Test fun `batch name numbers selected files in display order`() {
        assertEquals(
            mapOf("first" to "Holiday_1", "third" to "Holiday_2", "fourth" to "Holiday_3"),
            BatchFileNames.applyStem(selected, " Holiday "),
        )
    }

    @Test fun `single file uses batch name without number`() {
        assertEquals(mapOf("first" to "Holiday"), BatchFileNames.applyStem(selected.take(1), "Holiday"))
    }

    @Test fun `reset restores original stems after batch rename`() {
        assertEquals(
            mapOf("first" to "First", "third" to "Third", "fourth" to "Fourth"),
            BatchFileNames.defaults(selected),
        )
    }

    @Test fun `reset all replaces recovered captions with numbered platform names`() {
        val items = listOf(6, 7, 10).map { position ->
            MediaItem(
                id = "item-$position", providerId = "instagram", type = MediaType.PHOTO,
                originalName = if (position == 10) "Instagram_10.jpg"
                    else "our last day together 🖤 all orig_$position.jpg",
                downloadUrl = "https://example.com/$position.jpg", position = position,
            )
        }
        assertEquals(
            mapOf("item-6" to "Instagram_6", "item-7" to "Instagram_7",
                "item-10" to "Instagram_10"),
            BatchFileNames.platformDefaults(items),
        )
    }

    @Test fun `typing a common name replaces every source caption before download`() {
        val items = listOf(1, 2, 3).map { position ->
            MediaItem(
                id = "item-$position", providerId = "instagram", type = MediaType.PHOTO,
                originalName = "old caption_$position.jpg",
                downloadUrl = "https://example.com/$position.jpg", position = position,
            )
        }
        assertEquals(
            mapOf("item-1" to "New name_1", "item-2" to "New name_2",
                "item-3" to "New name_3"),
            BatchFileNames.commonInputNames(items, "New name"),
        )
        assertEquals(
            mapOf("item-1" to "old caption_1", "item-2" to "old caption_2",
                "item-3" to "old caption_3"),
            BatchFileNames.commonInputNames(items, ""),
        )
    }
}

package com.domenota.medialoader.ui.model

import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewSelectionTest {
    private fun item(id: String, type: MediaType) = MediaItem(id, "instagram", type, id, "https://example.org/$id")
    private val items = listOf(item("video", MediaType.VIDEO), item("audio", MediaType.AUDIO))

    @Test fun choosingAudioReplacesVideoAndChoosingVideoReplacesAudio() {
        assertEquals(setOf("audio"), togglePreviewSelection(items, setOf("video"), "audio"))
        assertEquals(setOf("video"), togglePreviewSelection(items, setOf("audio"), "video"))
    }

    @Test fun tappingSelectedRadioKeepsOneOptionSelected() {
        assertEquals(setOf("video"), togglePreviewSelection(items, setOf("video"), "video"))
        assertEquals(setOf("audio"), togglePreviewSelection(items, setOf("audio"), "audio"))
    }

    @Test fun videoAlternativesStartWithJustOneSelected() {
        assertEquals(setOf("video"), (items + item("other-video", MediaType.VIDEO)).defaultSelectedIds())
    }

    @Test fun photoSelectionStaysIndependentAndCanBeCleared() {
        assertEquals(setOf("photo", "audio"), togglePreviewSelection(items + item("photo", MediaType.PHOTO), setOf("photo", "video"), "audio"))
        assertEquals(setOf("audio"), togglePreviewSelection(items + item("photo", MediaType.PHOTO), setOf("photo", "audio"), "photo"))
    }
}

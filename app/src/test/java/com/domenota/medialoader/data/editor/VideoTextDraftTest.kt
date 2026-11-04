package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class VideoTextDraftTest {
    @Test fun emptyInputUsesPreviewHintWithoutChangingModelOrExportVisibility() {
        val draft = TextLayer(text = "")
        val preview = VideoTextDraft.preview(listOf(draft), draft.id).single()
        assertEquals("", draft.text)
        assertFalse(draft.visibleAt(0))
        assertEquals("Ваш текст", preview.text)
        assertTrue(preview.visibleAt(0))
        assertEquals("", VideoTextDraft.preview(listOf(draft), null).single().text)
    }
    @Test fun typedTextAndOtherLayersAreNeverReplacedByHint() {
        val first = TextLayer(text = "Ваш текст")
        val second = TextLayer(text = "")
        val result = VideoTextDraft.preview(listOf(first, second), first.id)
        assertEquals(listOf(first, second), result)
    }
}

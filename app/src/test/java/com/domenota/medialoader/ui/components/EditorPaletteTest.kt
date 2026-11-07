package com.domenota.medialoader.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorPaletteTest {
    @Test fun photoVideoAndTextToolsUseOneExistingGoldAccent() {
        assertEquals(Color(0xFFFFBD19), editorSliderYellow)
        assertEquals(editorSliderYellow, textEditorAccent)
    }
}

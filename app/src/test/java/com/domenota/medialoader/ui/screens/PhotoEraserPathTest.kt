package com.domenota.medialoader.ui.screens

import androidx.compose.ui.geometry.Offset
import com.domenota.medialoader.data.editor.PhotoPoint
import org.junit.Assert.*
import org.junit.Test

class PhotoEraserPathTest {
    @Test fun clipsAtBoundaryAndNeverConnectsOutsideSegments() {
        val rect=PhotoCanvasRect(100f,50f,200f,400f)
        assertNull(rect.clipSegment(Offset(20f,10f),Offset(80f,400f)))
        val entry=requireNotNull(rect.clipSegment(Offset(50f,250f),Offset(200f,250f)))
        assertEquals(PhotoPoint(0f,.5f),entry.first)
        assertEquals(PhotoPoint(.5f,.5f),entry.second)
        val exit=requireNotNull(rect.clipSegment(Offset(200f,250f),Offset(350f,250f)))
        assertEquals(PhotoPoint(1f,.5f),exit.second)
    }
}

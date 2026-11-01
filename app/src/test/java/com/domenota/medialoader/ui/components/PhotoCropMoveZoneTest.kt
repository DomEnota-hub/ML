package com.domenota.medialoader.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.domenota.medialoader.data.editor.CropFractions
import org.junit.Assert.*
import org.junit.Test

class PhotoCropMoveZoneTest {
    private val image = Rect(0f, 0f, 400f, 300f)

    @Test fun fullImageDoesNotHaveMoveSelectionArea() {
        assertFalse(isInsideCropMoveZone(Offset(200f, 150f),
            CropFractions(), image, 22f))
    }

    @Test fun centerOfSelectionCanMoveAndCornerRemainsResizable() {
        val crop = CropFractions(.2f, .2f, .2f, .2f)
        assertTrue(isInsideCropMoveZone(Offset(200f, 150f), crop, image, 22f))
        assertFalse(isInsideCropMoveZone(Offset(80f, 60f), crop, image, 22f))
        assertFalse(isInsideCropMoveZone(Offset(80f, 150f), crop, image, 22f))
        assertFalse(isInsideCropMoveZone(Offset(0f, 0f), crop, image, 22f))
    }

    @Test fun smallSelectionRetainsMovableInterior() {
        val crop = CropFractions(.45f, .45f, .45f, .45f)
        assertTrue(isInsideCropMoveZone(Offset(200f, 150f), crop, image, 22f))
    }
}

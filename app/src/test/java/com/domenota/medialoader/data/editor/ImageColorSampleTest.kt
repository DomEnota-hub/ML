package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class ImageColorSampleTest {
    @Test fun landscapeImageMappingAccountsForTopLetterbox() {
        assertEquals(ImagePixel(50, 0), ImageColorSample.pixel(100, 50, 200f, 200f, 100f, 50f))
        assertEquals(ImagePixel(99, 49), ImageColorSample.pixel(100, 50, 200f, 200f, 199.9f, 149.9f))
        assertNull(ImageColorSample.pixel(100, 50, 200f, 200f, 100f, 49f))
        assertNull(ImageColorSample.pixel(100, 50, 200f, 200f, 100f, 150f))
    }
    @Test fun portraitImageMappingAccountsForSideLetterbox() {
        assertEquals(ImagePixel(0, 50), ImageColorSample.pixel(50, 100, 200f, 200f, 50f, 100f))
        assertNull(ImageColorSample.pixel(50, 100, 200f, 200f, 49f, 100f))
    }
    @Test fun emptyViewportAndInvalidTouchHaveNoPixel() {
        assertNull(ImageColorSample.pixel(100, 50, 0f, 200f, 0f, 0f))
        assertNull(ImageColorSample.pixel(100, 50, 200f, 200f, Float.NaN, 0f))
    }
    @Test fun hexDisplaysExactRgbAndPreservesTransparencyWhenPresent() {
        assertEquals("#123456", ImageColorSample.hex(0xFF123456.toInt()))
        assertEquals("#80123456", ImageColorSample.hex(0x80123456.toInt()))
        assertEquals("#000000", ImageColorSample.hex(0xFF000000.toInt()))
    }
}

package com.domenota.medialoader.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FileSizeFormatterTest {
    @Test fun formatsAtMegabyteBoundary() {
        assertEquals("512.0 КБ", FileSizeFormatter.format(512L * 1024))
        assertEquals("1.0 МБ", FileSizeFormatter.format(1024L * 1024))
        assertEquals("21.3 МБ", FileSizeFormatter.format(22_334_259))
        assertEquals("Размер неизвестен", FileSizeFormatter.format(null))
    }
}

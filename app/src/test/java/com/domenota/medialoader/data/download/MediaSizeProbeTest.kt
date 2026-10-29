package com.domenota.medialoader.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSizeProbeTest {
    @Test fun contentDispositionKeepsReadableName() {
        assertEquals("holiday photo", filenameFromDisposition("inline; filename=\"holiday photo.jpg\""))
        assertEquals("Лето", filenameFromDisposition("attachment; filename*=UTF-8''%D0%9B%D0%B5%D1%82%D0%BE.jpg"))
    }

    @Test fun generatedNamesAreIgnored() {
        assertNull(filenameFromDisposition("inline; filename=\"803925802_1812008545991362.jpg\""))
        assertNull(filenameFromDisposition("attachment; filename=\"../../photo.jpg\""))
    }
}

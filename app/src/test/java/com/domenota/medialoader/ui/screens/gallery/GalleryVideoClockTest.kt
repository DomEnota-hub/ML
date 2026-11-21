package com.domenota.medialoader.ui.screens.gallery

import org.junit.Test
import org.junit.Assert.assertEquals

class GalleryVideoClockTest {
    @Test fun clockDisplaysMinutesAndHours() {
        assertEquals("00:00", galleryVideoClock(0L))
        assertEquals("00:19", galleryVideoClock(19_123L))
        assertEquals("01:05", galleryVideoClock(65_000L))
        assertEquals("1:02:03", galleryVideoClock(3_723_000L))
    }
}

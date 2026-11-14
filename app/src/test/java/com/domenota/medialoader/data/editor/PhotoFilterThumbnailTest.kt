package com.domenota.medialoader.data.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoFilterThumbnailTest {
    @Test fun preservesPortraitLandscapeAndSmallSources() {
        assertEquals(192 to 384, PhotoFilterThumbnail.dimensions(2000, 4000, 384))
        assertEquals(384 to 192, PhotoFilterThumbnail.dimensions(4000, 2000, 384))
        assertEquals(80 to 40, PhotoFilterThumbnail.dimensions(80, 40, 384))
        assertEquals(1 to 384, PhotoFilterThumbnail.dimensions(1, 9000, 384))
    }
}

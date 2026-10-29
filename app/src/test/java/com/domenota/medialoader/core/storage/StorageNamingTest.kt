package com.domenota.medialoader.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class StorageNamingTest {
    @Test fun preservesOriginalNameAndNumbersCollisions() {
        assertEquals("image.jpg", StorageNaming.availableName("image.jpg", emptySet()))
        assertEquals(
            "image_2.jpg",
            StorageNaming.availableName("image.jpg", setOf("image.jpg", "image_1.jpg")),
        )
        assertEquals("video_1", StorageNaming.availableName("video", setOf("video")))
    }

    @Test fun rejectsPathAsFilename() {
        assertThrows(IllegalArgumentException::class.java) {
            StorageNaming.availableName("../image.jpg", emptySet())
        }
    }

    @Test fun usesNextFreeSuffixForNumberedFallbackNames() {
        assertEquals(
            "Instagram_2.jpg",
            StorageNaming.availableName("Instagram.jpg", setOf("Instagram.jpg", "Instagram_1.jpg")),
        )
    }

    @Test fun fallbackNameIsInstagramNotAnOpaqueCode() {
        assertEquals("Instagram.jpg", StorageNaming.mediaFileName(null, null, "jpg"))
        assertEquals("Instagram.mp4", StorageNaming.mediaFileName("", null, "mp4"))
        assertEquals("clip.mp4", StorageNaming.mediaFileName("clip", null, "mp4"))
    }

    @Test fun keepsCaptionWithEmojiInsteadOfReplacingItWithInstagram() {
        assertEquals("Увидимся на месте 😉.mp4",
            StorageNaming.normalizedMediaName("Увидимся на месте 😉.mp4", null))
    }

    @Test fun carouselIsNumberedFromTheFirstElement() {
        assertEquals("photo_1.jpg", StorageNaming.mediaFileName("photo", 1, "jpg"))
        assertEquals("photo_2.mp4", StorageNaming.mediaFileName("photo", 2, "mp4"))
        assertEquals("Instagram_3.jpg", StorageNaming.mediaFileName(null, 3, "jpg"))
    }

    @Test fun onlyPlainStemsAreMeaningful() {
        assertEquals("abc_12-x", StorageNaming.meaningfulStem("abc_12-x"))
        assertNull(StorageNaming.meaningfulStem(""))
        assertEquals("a b", StorageNaming.meaningfulStem("a b"))
        assertNull(StorageNaming.meaningfulStem("803925802_1812008545991362"))
        assertNull(StorageNaming.meaningfulStem("803925802_1812008545991362_n"))
        assertNull(StorageNaming.meaningfulStem("AQMMdwFM-4TOsTqsyjzXNrv980hp1"))
        assertNull(StorageNaming.meaningfulStem("../x"))
        assertNull(StorageNaming.meaningfulStem(null))
    }

    @Test fun generatedMetadataIsNotKeptAsAFileName() {
        assertEquals("Instagram_2.jpg", StorageNaming.normalizedMediaName("803925799_181200858_n.jpg", 2))
        assertEquals("holiday_2.jpg", StorageNaming.normalizedMediaName("holiday_2.jpg", 2))
    }

    @Test fun editableNamesHideTheManagedExtension() {
        assertEquals("Страшная история", StorageNaming.editableStem("Страшная история.m4a"))
        assertEquals("Видео", StorageNaming.editableStem("Видео.mp4"))
        assertEquals("archive.part", StorageNaming.editableStem("archive.part.mp4"))
        assertEquals("без расширения", StorageNaming.editableStem("без расширения"))
        assertEquals(".hidden", StorageNaming.editableStem(".hidden"))
    }

    @Test fun customNameAddsTheOriginalExtensionExactlyOnce() {
        assertEquals("Страшная история.m4a", StorageNaming.customName("Страшная история", "old.m4a"))
        assertEquals("Страшная история.m4a", StorageNaming.customName("Страшная история.m4a", "old.m4a"))
        assertEquals("Видео.mp4", StorageNaming.customName("Видео", "old.mp4"))
        assertEquals("Видео.mp4", StorageNaming.customName("Видео.MP4", "old.mp4"))
    }

    @Test fun defaultFolderStaysDownloadMediaLoader() {
        assertEquals("Download/MediaLoader/", StorageNaming.DEFAULT_RELATIVE_DIRECTORY)
    }
}

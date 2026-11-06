package com.domenota.medialoader.ui.screens.gallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GalleryAlbumFileSortTest {
    private fun media(id: String, file: String, created: Long = 0L, size: Long? = null) =
        DownloadEntity(id, "device", file, MediaType.VIDEO, DownloadState.COMPLETED,
            "content://media/external/video/media/$id", size, created)

    @Test fun filenameSortUsesActualEpisodeNumbersInsteadOfStringOrder() {
        val rows = listOf(
            media("5", "Отбросы 5 2.mp4"),
            media("10", "Отбросы 1 10.mp4"),
            media("2", "Отбросы 1 2.mp4"),
            media("1", "Отбросы 1 1.mp4"),
            media("20", "Отбросы 2 1.mp4"),
        )
        val ordered = GalleryAlbumFileOrder.sorted(rows, GalleryAlbumFileSort.NAME_ASC)
        assertEquals(listOf("1", "2", "10", "20", "5"), ordered.map { it.id })
        assertEquals(listOf("5", "20", "10", "2", "1"),
            GalleryAlbumFileOrder.sorted(rows, GalleryAlbumFileSort.NAME_DESC).map { it.id })
    }

    @Test fun numericRunsAvoidOverflowAndStillSortNaturally() {
        assertTrue(GalleryAlbumFileOrder.compareNatural(
            "Episode 999999999999999999999", "Episode 1000000000000000000000") < 0)
    }

    @Test fun dateAndSizeSortWithUnknownSizeLast() {
        val rows = listOf(
            media("a", "a", 100, 200),
            media("b", "b", 200, null),
            media("c", "c", 50, 50),
        )
        assertEquals(listOf("b", "a", "c"),
            GalleryAlbumFileOrder.sorted(rows, GalleryAlbumFileSort.DATE_NEW).map { it.id })
        assertEquals(listOf("c", "a", "b"),
            GalleryAlbumFileOrder.sorted(rows, GalleryAlbumFileSort.SIZE_SMALL).map { it.id })
        assertEquals(listOf("a", "c", "b"),
            GalleryAlbumFileOrder.sorted(rows, GalleryAlbumFileSort.SIZE_LARGE).map { it.id })
    }

    @Test fun settingsRememberSortIndependentlyForDifferentAlbums() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("gallery_album_file_order", Context.MODE_PRIVATE).edit().clear().commit()
        val store = GalleryAlbumFileSortStore(context)
        store.save("PHONE", "device:serials", GalleryAlbumFileSort.NAME_ASC)
        assertEquals(GalleryAlbumFileSort.NAME_ASC, store.read("PHONE", "device:serials"))
        assertEquals(GalleryAlbumFileSort.DATE_NEW, store.read("PHONE", "device:other"))
        assertEquals(GalleryAlbumFileSort.DATE_NEW, store.read("APP", "device:serials"))
    }
}

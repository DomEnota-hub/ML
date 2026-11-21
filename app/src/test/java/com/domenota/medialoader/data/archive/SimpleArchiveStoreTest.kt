package com.domenota.medialoader.data.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SimpleArchiveStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val index get() = File(context.noBackupFilesDir, "simple-media-archive-v1.json")
    private fun clearIndex() {
        listOf(index, File(index.path + ".bak"), File(index.path + ".new")).forEach { it.delete() }
    }
    @Before fun before() = clearIndex()
    @After fun after() = clearIndex()

    private fun row(id: String, file: File, kind: MediaType) =
        DownloadEntity(id, "device", file.name, kind, DownloadState.COMPLETED,
            android.net.Uri.fromFile(file).toString(), file.length(), 1234L)

    @Test fun selectedMediaFromThreeModulesSurviveStoreRecreationAndReturnWithoutChangingBytes() = runBlocking<Unit> {
        val folder = File(context.cacheDir, "simple-archive-test").apply { mkdirs() }
        val photo = File(folder, "photo.jpg").apply { writeText("photo-original") }
        val video = File(folder, "video.mp4").apply { writeText("video-original") }
        val audio = File(folder, "audio.mp3").apply { writeText("audio-original") }
        val neighbor = File(folder, "neighbor.jpg").apply { writeText("untouched") }
        val files = listOf(photo, video, audio, neighbor)
        val original = files.associateWith { it.readBytes() }
        val first = SimpleArchiveStore(context)
        first.archive(listOf(row("download", photo, MediaType.PHOTO)), ArchiveEntrySource.DOWNLOADER)
        first.archive(listOf(row("gallery", video, MediaType.VIDEO)), ArchiveEntrySource.GALLERY)
        first.archive(listOf(row("music", audio, MediaType.AUDIO)), ArchiveEntrySource.MUSIC)
        val reopened = SimpleArchiveStore(context)
        assertEquals(setOf("download", "gallery", "music"), reopened.refresh().map { it.catalogId }.toSet())
        assertFalse(File(folder, ".nomedia").exists())
        assertFalse(SimpleArchivePolicy.isArchived(reopened.records.value,
            SimpleArchiveCandidate("neighbor", android.net.Uri.fromFile(neighbor).toString(), MediaType.PHOTO, neighbor.length())))
        reopened.restore(setOf("gallery", "music"))
        assertEquals(listOf("download"), SimpleArchiveStore(context).refresh().map { it.catalogId })
        for ((file, bytes) in original) assertArrayEquals(bytes, file.readBytes())
        folder.deleteRecursively()
    }

    @Test fun damagedIndexIsNotOverwrittenAndPrivateRowsAreNotImported() = runBlocking<Unit> {
        val file = File(context.cacheDir, "archive-private-test.jpg").apply { writeText("original") }
        val store = SimpleArchiveStore(context)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.archive(listOf(row("private", file, MediaType.PHOTO).copy(isPrivate = true)), ArchiveEntrySource.GALLERY) }
        }
        assertTrue(store.refresh().isEmpty())
        index.writeText("invalid json")
        val before = index.readBytes()
        assertThrows(IOException::class.java) { runBlocking { store.refresh() } }
        assertThrows(IOException::class.java) {
            runBlocking { store.archive(listOf(row("normal", file, MediaType.PHOTO)), ArchiveEntrySource.GALLERY) }
        }
        assertArrayEquals(before, index.readBytes())
        assertEquals("original", file.readText())
        file.delete()
    }

    @Test fun incompleteFirstWriteIsNotTreatedAsEmptyOrOverwritten() = runBlocking<Unit> {
        val unfinished = File(index.path + ".new").apply { writeText("unfinished") }
        val before = unfinished.readBytes()
        val media = File(context.cacheDir, "archive-unfinished-test.jpg").apply { writeText("original") }
        try {
            val store = SimpleArchiveStore(context)
            assertThrows(IOException::class.java) { runBlocking { store.refresh() } }
            assertThrows(IOException::class.java) {
                runBlocking { store.archive(listOf(row("normal", media, MediaType.PHOTO)), ArchiveEntrySource.GALLERY) }
            }
            assertArrayEquals(before, unfinished.readBytes())
            assertEquals("original", media.readText())
            assertFalse(index.exists())
        } finally { media.delete() }
    }
}

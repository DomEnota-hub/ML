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

    @Test fun virtualFolderSixOf866AcrossDirectoriesPreservesAllBytesAndGroupAfterRestart() = runBlocking<Unit> {
        val root = File(context.cacheDir, "archive-qa226-866").apply { mkdirs() }
        try {
            val files = (1..866).map { n ->
                val parent = File(root, if (n % 2 == 0) "even" else "odd").apply { mkdirs() }
                File(parent, "photo-$n.jpg").apply { writeText("original-$n") }
            }
            val document = File(root, "notes.md").apply { writeText("not a media file") }
            val original = (files + document).associateWith { it.readBytes() }
            val group = SimpleArchiveGroup(SimpleArchiveGroupKind.VIRTUAL_FOLDER, "folder-test", "Тест")
            val chosen = files.take(6).mapIndexed { n, file -> row("media-$n", file, MediaType.PHOTO) }
            SimpleArchiveStore(context).archive(chosen, ArchiveEntrySource.DOWNLOADER, group)
            val reopened = SimpleArchiveStore(context)
            val records = reopened.refresh()
            assertEquals(6, records.size)
            assertTrue(records.all { it.group == group })
            reopened.restore(setOf("media-0"))
            val partial = SimpleArchiveStore(context).refresh()
            assertEquals(5, partial.size)
            assertTrue(partial.all { it.group == group })
            SimpleArchiveStore(context).restore(partial.map { it.catalogId }.toSet())
            assertTrue(SimpleArchiveStore(context).refresh().isEmpty())
            assertFalse(root.walkTopDown().any { it.name == ".nomedia" })
            for ((file, bytes) in original) assertArrayEquals(bytes, file.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun previousV1WithoutGroupLoadsAndConflictingBatchLeavesPersistedIndexUntouched() = runBlocking<Unit> {
        val first = File(context.cacheDir, "archive-legacy-qa226.jpg").apply { writeText("original") }
        val second = File(context.cacheDir, "archive-other-qa226.jpg").apply { writeText("other") }
        try {
            val old = row("old", first, MediaType.PHOTO)
            SimpleArchiveStore(context).archive(listOf(old), ArchiveEntrySource.GALLERY)
            val legacy = org.json.JSONObject(index.readText())
            legacy.getJSONArray("files").getJSONObject(0).remove("group")
            index.writeText(legacy.toString())
            val store = SimpleArchiveStore(context)
            assertNull(store.refresh().single().group)
            val before = index.readBytes()
            assertThrows(IllegalStateException::class.java) {
                runBlocking { store.archive(listOf(row("new", second, MediaType.PHOTO),
                    old.copy(id = "alias", sizeBytes = first.length() + 10)), ArchiveEntrySource.GALLERY) }
            }
            assertArrayEquals(before, index.readBytes())
            assertEquals(listOf("old"), SimpleArchiveStore(context).refresh().map { it.catalogId })
            assertEquals("original", first.readText())
            assertEquals("other", second.readText())
        } finally { first.delete(); second.delete() }
    }
}

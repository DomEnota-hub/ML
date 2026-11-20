package com.domenota.medialoader.data.catalog.visibility

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SelectiveArchiveMoveJournalTest {
    private val signature="a".repeat(64)
    private val links="b".repeat(64)
    private val token="A".repeat(32)
    private val origin=SelectiveArchiveIdentity(
        "track-1","content://media/external/audio/media/101",101,
        "external_primary","Music/Collection/","song.mp3",512,signature,
        1_700_000_000_000L,links)
    private val cp=SelectiveArchiveCheckpoint("folder",listOf(origin),1_700_000_000_000L)
    private val target="Music/MediaLoaderArchive/$token/"

    @Test fun transitionsAreDurableAfterProcessRestartAndCannotSkipRestore() = runBlocking<Unit> {
        val dir=Files.createTempDirectory("sel-journal").toFile()
        try {
            val first=SelectiveArchiveMoveJournal(dir)
            val base=first.begin(cp,origin,token,target)
            assertEquals(SelectiveArchiveMovePhase.PREPARED,base.phase)
            assertEquals(base,SelectiveArchiveMoveJournal(dir).read("folder","track-1"))
            val intent=first.transition(base,SelectiveArchiveMovePhase.HIDE_INTENT)
            assertEquals(1L,intent.sequence)
            val afterCrash=SelectiveArchiveMoveJournal(dir)
            assertEquals(intent,afterCrash.begin(cp,origin,token,target))
            val hidden=afterCrash.transition(intent,SelectiveArchiveMovePhase.HIDDEN_VERIFIED)
            val restoring=afterCrash.transition(hidden,SelectiveArchiveMovePhase.RESTORE_INTENT)
            val returned=afterCrash.transition(restoring,SelectiveArchiveMovePhase.RESTORED_VERIFIED)
            assertEquals(4L,returned.sequence)
            assertEquals(returned,SelectiveArchiveMoveJournal(dir).read("folder","track-1"))
            assertThrows(IllegalStateException::class.java) {
                runBlocking { first.transition(returned,SelectiveArchiveMovePhase.HIDE_INTENT) }
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun staleWriterAndDifferentTokenDoNotOverwriteAReceipt() = runBlocking<Unit> {
        val dir=Files.createTempDirectory("sel-journal-stale").toFile()
        try {
            val a=SelectiveArchiveMoveJournal(dir)
            val before=a.begin(cp,origin,token,target)
            val next=a.transition(before,SelectiveArchiveMovePhase.HIDE_INTENT)
            assertThrows(IllegalStateException::class.java) {
                runBlocking { a.transition(before,SelectiveArchiveMovePhase.HIDE_INTENT) }
            }
            assertThrows(IllegalStateException::class.java) {
                runBlocking { a.begin(cp,origin,"B".repeat(32),target) }
            }
            assertEquals(next,a.read("folder","track-1"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun corruptedFileRemainsUnchangedAndJournalCannotBeReplaced() = runBlocking<Unit> {
        val dir=Files.createTempDirectory("sel-journal-corrupt").toFile()
        try {
            val store=SelectiveArchiveMoveJournal(dir)
            store.begin(cp,origin,token,target)
            val saved=checkNotNull(dir.listFiles()?.singleOrNull { it.name.endsWith(".binding") })
            val bytes=saved.readBytes()
            saved.writeBytes(bytes.copyOf().apply { this[4]=(this[4].toInt() xor 0x35).toByte() })
            val corrupted=saved.readBytes()
            assertThrows(IllegalStateException::class.java) {
                runBlocking { store.begin(cp,origin,token,target) }
            }
            assertArrayEquals(corrupted,saved.readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test fun unknownPhaseAndInvalidPathAreRejected() = runBlocking<Unit> {
        val dir=Files.createTempDirectory("sel-journal-path").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { SelectiveArchiveMoveJournal(dir).begin(cp,origin,token,"../Music/") }
            }
            assertTrue(dir.listFiles().isNullOrEmpty())
        } finally { dir.deleteRecursively() }
    }
}

package com.domenota.medialoader.data.catalog.visibility

import android.content.Context
import com.domenota.medialoader.data.storage.HistoricalRecoverySignals
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exact 1.7.203 pending SHOW plan; no real Android media are created or deleted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class VisibilityPrivatePublicationGateTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val folder get() = File(context.noBackupFilesDir,"folder-visibility-v1")
    private val journal get() = FileVisibilityBatchJournal(File(folder,"batch.bin"))
    private val path = "Pictures/MediaLoader/"
    private fun plannedRestore(): VisibilityBatchRecord {
        val original = VisibilityPublicOrigin(
            "content://media/external_primary/images/media/41", "external_primary",
            path, "hidden-photo.jpg", "image/jpeg",
            "/storage/emulated/0/Pictures/MediaLoader/hidden-photo.jpg",
            4L,1_700_000_000L,1_700_000_000L,1_700_000_000_000L,null)
        val member = VisibilityMoveRecord(
            "operation-1","folder-1","member-1",VisibilityDirection.SHOW,
            "file:///storage/emulated/0/Documents/MediaLoaderHidden/token/hidden-photo.jpg",
            original.name,original.modifiedMillis,VisibilityFingerprint(4L,"a".repeat(64)),
            "token-123456789012",publicOrigin=original)
        return VisibilityBatchRecord("batch-1","folder-1",VisibilityDirection.SHOW,listOf(member))
    }

    @Test fun outstandingShowReservesUnpublishedOriginalNameWithoutBlockingDifferentName() = runBlocking {
        folder.deleteRecursively()
        try {
            journal.replace(null,plannedRestore())
            assertTrue(FolderVisibilityManager.hasPendingJournal(context))
            assertEquals(setOf("hidden-photo.jpg"),
                FolderVisibilityManager.reservedPendingPublicNames(context,path))
            assertEquals(emptySet<String>(),
                FolderVisibilityManager.reservedPendingPublicNames(context,"Music/MediaLoader/"))
            // Different-named independent private exports may use a different
            // MediaStore row without stealing the hidden file's target.
        } finally { folder.deleteRecursively() }
    }

    @Test fun corruptOrMissingExactPlanBlocksPrivateExportFailClosed() = runBlocking {
        folder.deleteRecursively()
        try {
            folder.mkdirs()
            File(folder,"move.bin").writeText("corrupt synthetic journal")
            assertTrue(FolderVisibilityManager.hasPendingJournal(context))
            assertTrue(runCatching {
                FolderVisibilityManager.reservedPendingPublicNames(context,path)
            }.isFailure)
        } finally { folder.deleteRecursively() }
    }

    @Test fun unrelatedPrivatePublishMayStartWithPendingFolderButSerializesActualMove() {
        HistoricalRecoverySignals.markFolderVisibilityPending(true)
        try {
            HistoricalRecoverySignals.beginIndependentPrivatePublication(context)
            try {
                HistoricalRecoverySignals.requireIndependentPrivatePublication()
                assertTrue(runCatching { HistoricalRecoverySignals.beginFolderVisibility() }.isFailure)
                assertTrue(runCatching { HistoricalRecoverySignals.requireIdle() }.isFailure)
            } finally { HistoricalRecoverySignals.endIndependentPrivatePublication() }
            assertTrue(runCatching { HistoricalRecoverySignals.requireIdle() }.isFailure)
        } finally { HistoricalRecoverySignals.markFolderVisibilityPending(false) }
        HistoricalRecoverySignals.requireIdle()
    }
}

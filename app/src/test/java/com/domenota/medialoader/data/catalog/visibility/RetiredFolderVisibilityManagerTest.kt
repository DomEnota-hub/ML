package com.domenota.medialoader.data.catalog.visibility

import androidx.room.Room
import com.domenota.medialoader.core.database.*
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.LibraryMediaRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Real entry points reject retired physical operations before consent, DB or file mutation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class RetiredFolderVisibilityManagerTest {
    @Test fun realEntryPointsLeaveLegacyJournalCatalogAndOriginalUntouched() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val db=Room.inMemoryDatabaseBuilder(context,HistoryDatabase::class.java).allowMainThreadQueries().build()
        val original=File(context.filesDir,"legacy-test-original.jpg")
        val root=File(context.noBackupFilesDir,"folder-visibility-v1")
        val journal=File(root,"batch.bin")
        val bytes=byteArrayOf(10,20,30,40)
        try {
            original.writeBytes(bytes)
            val uri=original.toURI().toString()
            val row=DownloadEntity("retired-test","instagram","legacy-test-original.jpg",MediaType.PHOTO,DownloadState.COMPLETED,uri,4,123)
            db.downloadDao().upsert(row)
            db.catalogDao().insertFolder(CatalogFolderEntity("folder","Folder","folder",1))
            db.catalogDao().saveAssignment(CatalogAssignmentEntity(row.id,"folder",true))
            val member=VisibilityMoveRecord("op","folder",row.id,VisibilityDirection.HIDE,uri,original.name,123L,
                VisibilityFingerprint(4,"a".repeat(64)),"token")
            val batch=VisibilityBatchRecord("batch","folder",VisibilityDirection.HIDE,listOf(member))
            FileVisibilityBatchJournal(journal).replace(null,batch)
            val prior=journal.readBytes()
            val manager=FolderVisibilityManager(context,db)
            val ref=LibraryMediaRef(row.id,setOf(uri),row,row.id)
            var approvals=0
            val actions:List<suspend ()->Any?> = listOf(
                { manager.preview("folder",true) },
                { manager.setHidden("folder",true,{ approvals++;true }) },
                { manager.changeMember(ref,null,{ approvals++;true }) },
                { manager.resume { approvals++;true } }
            )
            for(action in actions) {
                val error=runCatching { action() }.exceptionOrNull()
                assertTrue(error is IllegalStateException)
                assertEquals(RetiredFolderVisibilityTransfer.MESSAGE,error?.message)
            }
            assertEquals(0,approvals)
            assertArrayEquals(bytes,original.readBytes())
            assertArrayEquals(prior,journal.readBytes())
            assertEquals(row,db.downloadDao().byId(row.id))
            assertEquals("folder",db.catalogDao().assignmentById(row.id)?.folderId)
            assertFalse(manager.isHidden("folder"))
            assertTrue(FolderVisibilityManager.hasPendingJournal(context))
            val pending=manager.pending()
            assertTrue(pending.pending)
            assertEquals(listOf(member),pending.members)
        } finally {
            root.deleteRecursively()
            original.delete()
            db.close()
        }
    }
}

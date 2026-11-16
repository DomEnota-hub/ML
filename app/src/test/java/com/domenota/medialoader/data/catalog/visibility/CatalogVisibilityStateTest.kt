package com.domenota.medialoader.data.catalog.visibility

import androidx.room.Room
import com.domenota.medialoader.core.database.*
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class CatalogVisibilityStateTest {
    @Test fun modeAndReceiptsSurviveRepositoryRecreationWithoutChangingExistingCatalog() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),HistoryDatabase::class.java).allowMainThreadQueries().build()
        try {
            val origin = VisibilityPublicOrigin("content://media/external/images/media/17","external_primary","Pictures/","photo.jpg","image/jpeg",
                "/storage/emulated/0/Pictures/photo.jpg",20,1700000000,1690000000,null,"app")
            val member = VisibilityMoveRecord("hide","f","a",VisibilityDirection.HIDE,origin.uri,origin.name,origin.modifiedMillis,
                VisibilityFingerprint(20,"a".repeat(64)),"0123456789abcdef",publicOrigin=origin)
            val receipt = member.copy(destinationUri="file:///hidden/photo.jpg",phase=VisibilityMovePhase.CLEANED)
            val row = DownloadEntity("a","instagram",origin.name,MediaType.PHOTO,DownloadState.COMPLETED,receipt.destinationUri,20,123)
            db.downloadDao().upsert(row); db.catalogDao().insertFolder(CatalogFolderEntity("f","Folder","folder",1))
            db.catalogDao().saveAssignment(CatalogAssignmentEntity("a","f",true))
            val state = CatalogVisibilityState(db)
            assertFalse(state.isHidden("f")); assertTrue(state.hiddenFolderIds.first().isEmpty())
            state.rememberCompleted(receipt); assertFalse(state.isHidden("f"))
            val batch = VisibilityBatchRecord("batch","f",VisibilityDirection.HIDE,listOf(member),1)
            state.commitFolder(batch,emptySet()); val recreated = CatalogVisibilityState(db)
            assertTrue(recreated.isHidden("f")); assertEquals(setOf("f"),recreated.hiddenFolderIds.first())
            assertEquals(receipt,recreated.receipt("a")); recreated.commitFolder(batch,emptySet())
            assertEquals(row,db.downloadDao().byId("a")); assertTrue(db.catalogDao().assignmentById("a")!!.isFavorite)
            try { recreated.forgetVisibleFolder("f"); fail("Hidden origin must remain") } catch (_:IllegalStateException) { }
        } finally { db.close() }
    }
    @Test fun missingReceiptOrChangedMembershipCannotCommitMode() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),HistoryDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.catalogDao().insertFolder(CatalogFolderEntity("f","Folder","folder",1))
            val state = CatalogVisibilityState(db)
            val batch = VisibilityBatchRecord("batch","f",VisibilityDirection.HIDE,emptyList())
            try { state.commitFolder(batch,setOf("external")); fail("Membership mismatch") } catch (_:IllegalStateException) { }
            assertFalse(state.isHidden("f")); state.commitFolder(batch,emptySet()); assertTrue(state.isHidden("f"))
            val show = batch.copy(id="show",direction=VisibilityDirection.SHOW)
            state.commitFolder(show,emptySet()); assertFalse(state.isHidden("f")); state.forgetVisibleFolder("f")
        } finally { db.close() }
    }
}

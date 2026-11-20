package com.domenota.medialoader.data.catalog.visibility

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class NomediaFolderRepositoryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("folder_nomedia_v1",Context.MODE_PRIVATE)
    private fun reset() { assertTrue(prefs.edit().clear().commit()) }
    private val tree="content://com.android.externalstorage.documents/tree/primary%3ATest"

    @Test fun persists1_8_46FolderRecordsAfterNewRepositoryAndNoHistoryMutation() {
        for (count in listOf(1,8,46)) {
            reset()
            val store=NomediaFolderRepository(context)
            val history=(1..count).associate { it.toString() to "media://source/$it" }
            for (i in 1..count) {
                store.write(NomediaFolderRecord("folder-$i",tree,true,NomediaMarkerState.FOREIGN_MARKER))
            }
            val restored=NomediaFolderRepository(context).snapshot()
            assertFalse(restored.unknown)
            assertEquals(count,restored.folders.size)
            assertEquals(count,restored.hiddenFolderIds.size)
            assertEquals((1..count).associate { it.toString() to "media://source/$it" },history)
        }
    }

    @Test fun corruptOrUnknownVersionRegistryDoesNotBecomePubliclyEmpty() {
        reset()
        prefs.edit().putString("registry","{\"version\":999,\"entries\":[]}").commit()
        val repository=NomediaFolderRepository(context)
        assertTrue(repository.snapshot().unknown)
        assertThrows(IllegalStateException::class.java) {
            repository.write(NomediaFolderRecord("folder",tree,true,NomediaMarkerState.PENDING_HIDE))
        }
        reset()
        prefs.edit().putString("registry","not json").commit()
        assertTrue(NomediaFolderRepository(context).snapshot().unknown)
    }

    @Test fun reportsTwoStatesWithoutExposingTreeOrHistoryIdentifiers() {
        reset()
        val store=NomediaFolderRepository(context)
        store.write(NomediaFolderRecord("safe-folder",tree,true,NomediaMarkerState.OWN_MARKER,"marker-id"))
        val summary=store.statusText("safe-folder").orEmpty()
        assertTrue(summary.contains("ML: скрыто"))
        assertTrue(summary.contains(".nomedia создан"))
        assertFalse(summary.contains("primary"))
        assertFalse(summary.contains("marker-id"))
        val reopened=NomediaFolderRepository(context).snapshot()
        assertEquals("marker-id",reopened.folders["safe-folder"]?.ownedDocumentId)
    }


    @Test fun broadOrLegacyTreesAreRejectedBeforeSAFMutation() {
        val rejected=listOf("primary:","primary:Pictures","primary:DCIM","primary:Music",
            "primary:Documents","primary:Documents/MediaLoaderHidden",
            "primary:Documents/MediaLoaderHidden/subfolder",
            "primary:Android/data","primary:Pictures/../DCIM","primary:/Pictures",
            "9A2B:DCIM","9A2B:Android/media")
        rejected.forEach { assertFalse("Accepted forbidden tree $it",nomediaSafTreeEligible(it)) }
        assertTrue(nomediaSafTreeEligible("primary:Pictures/MLSamples"))
        assertTrue(nomediaSafTreeEligible("primary:Music/TestAlbum"))
    }

    @Test fun unknownForeignOrPendingDoesNotDeleteMarkerWhenDeletingFolderMetadata() {
        reset()
        val store=NomediaFolderRepository(context)
        store.write(NomediaFolderRecord("folder",tree,true,NomediaMarkerState.FOREIGN_MARKER))
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { store.releaseShownFolder("folder") }
        }
        assertTrue(store.snapshot().hiddenFolderIds.contains("folder"))
    }
    @Test fun oldSafRegistryRemainsSafAndDirectProvenanceRoundTrips() {
        reset()
        val store=NomediaFolderRepository(context)
        val localTree="content://com.android.externalstorage.documents/tree/primary%3APictures%2FAlbum"
        val record=NomediaFolderRecord("direct-photo",localTree,true,
            NomediaMarkerState.OWN_MARKER,
            ownedDocumentId="ml-archive-v1:11111111-1111-1111-1111-111111111111",
            accessMode=NomediaAccessMode.MANAGED_LOCAL,ownedMarkerBytes=53L)
        store.write(record)
        store.write(NomediaFolderRecord("old-saf",tree,true,NomediaMarkerState.FOREIGN_MARKER))
        val reopened=NomediaFolderRepository(context).snapshot()
        assertFalse(reopened.unknown)
        assertEquals(record,reopened.folders["direct-photo"])
        assertEquals(NomediaAccessMode.SAF,reopened.folders["old-saf"]?.accessMode)
        assertEquals(0L,reopened.folders["old-saf"]?.ownedMarkerBytes)
        reset()
    }

}

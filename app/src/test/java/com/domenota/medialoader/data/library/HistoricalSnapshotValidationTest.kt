package com.domenota.medialoader.data.library

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class HistoricalSnapshotValidationTest {
    @Test fun absentCatalogCacheNeverAcceptsLaterCorruptEmptyOrInvalidDocument() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val prefs=context.getSharedPreferences("external_media_catalog",0)
        prefs.edit().clear().commit()
        val repo=ExternalMediaCatalogRepository(context)
        assertEquals("",repo.snapshotHistoricalMedia())
        for(raw in listOf("","{bad","{\"version\":99,\"entries\":[]}")) {
            prefs.edit().putString("catalog",raw).commit()
            try { repo.snapshotHistoricalMedia(); fail("Corrupt current document must fail closed") } catch(_:Exception) { }
        }
        prefs.edit().remove("catalog").commit()
        assertEquals("",repo.snapshotHistoricalMedia())
    }
    @Test fun absentPlaylistCacheNeverAcceptsLaterCorruptEmptyDocument() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val prefs=context.getSharedPreferences("media_playlists",0)
        prefs.edit().clear().commit()
        val repo=PlaylistRepository(context)
        assertEquals("",repo.snapshotHistoricalMedia())
        for(raw in listOf("","{bad","[{}]")) {
            prefs.edit().putString("playlists",raw).commit()
            try { repo.snapshotHistoricalMedia(); fail("Corrupt current document must fail closed") } catch(_:Exception) { }
        }
        prefs.edit().putString("playlists","[]").commit()
        assertEquals("[]",repo.snapshotHistoricalMedia())
    }
}

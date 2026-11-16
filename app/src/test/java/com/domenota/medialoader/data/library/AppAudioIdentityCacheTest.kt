package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.data.playback.PublicMediaIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class AppAudioIdentityCacheTest {
    @Test fun exactGenerationAndCurrentRowAreRequiredAndStaleWritersCannotRestoreRevokedCache()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val row=DownloadEntity("audio","local","a.mp3",MediaType.AUDIO,DownloadState.COMPLETED,"content://media/external/audio/media/42",123L,456L)
        val uri=requireNotNull(row.savedUri)
        val keys=PublicMediaIdentity.uriKeys(uri)+"file-stat:1:2:123:4"
        val ref=LibraryMediaRef(row.id,keys+row.id,row,row.id)
        val stamp=PhoneStoreStamp("v1","g1")
        val cache=AppAudioIdentityCache(context)
        cache.write(listOf(ref),stamp,"public",emptySet())
        assertEquals(keys,AppAudioIdentityCache(context).read(listOf(row),stamp,"public",emptySet())[uri])
        assertTrue(cache.read(listOf(row),stamp.copy(generation="g2"),"public",emptySet()).isEmpty())
        assertTrue(cache.read(listOf(row),stamp.copy(generation=null),"public",emptySet()).isEmpty())
        assertTrue(cache.read(listOf(row.copy(sizeBytes=124)),stamp,"public",emptySet()).isEmpty())
        assertTrue(cache.read(listOf(row.copy(isPrivate=true)),stamp,"public",emptySet()).isEmpty())
        assertTrue(cache.read(listOf(row),stamp,"other",emptySet()).isEmpty())
        assertTrue(cache.read(listOf(row),stamp,"public",setOf("file-stat:1:2:123:4")).isEmpty())
        PersistentProjectionCache.clearAll(context)
        cache.write(listOf(ref),stamp,"public",emptySet()){false}
        assertTrue(cache.read(listOf(row),stamp,"public",emptySet()).isEmpty())
    }
}

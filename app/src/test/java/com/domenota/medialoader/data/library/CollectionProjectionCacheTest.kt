package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.database.MediaCollectionMemberEntity
import com.domenota.medialoader.core.model.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CollectionProjectionCacheTest {
    private val app = RuntimeEnvironment.getApplication()
    private val row = DownloadEntity("app1", "local", "photo.jpg", MediaType.PHOTO, DownloadState.COMPLETED,
        "content://media/external/images/media/42", 123L, 456L)
    private val ref = LibraryMediaRef(row.id, setOf(row.id, "uri:${row.savedUri}", "file-stat:test"), row, row.id)
    private val member = MediaCollectionMemberEntity("m1", "c1", MediaOrganizationPolicy.encode(MediaOrganizationPolicy.keys(ref)), null, 1L)
    private val stamp = PhoneStoreStamp(null, null)
    private fun snapshot(pending: Set<String> = emptySet()) = AddressedCollection(listOf(ref), pending, emptyMap(),
        checkedMembers = mapOf(member.id to member.identityJson), resolved = mapOf(member.id to ref))

    @Test fun restartRestoresExactAppMemberButNoFreshStatus() = runBlocking {
        CollectionProjectionCache(app).write("c1", listOf(member), snapshot(), emptySet(), stamp)
        val cached = CollectionProjectionCache(app).read("c1", listOf(member), mapOf(row.id to row), emptySet())
        assertEquals(ref, cached?.resolved?.get(member.id))
        assertTrue(cached!!.statuses.isEmpty())
        assertNull(cached.verifiedStoreStamp)
    }

    @Test fun changedHistoryPrivacyMembershipOrIdentityCannotPublishOldRow() = runBlocking {
        val cache = CollectionProjectionCache(app)
        cache.write("c1", listOf(member), snapshot(), emptySet(), stamp)
        assertNull(cache.read("c1", listOf(member), emptyMap(), emptySet()))
        assertNull(cache.read("c1", listOf(member), mapOf(row.id to row.copy(isPrivate = true)), emptySet()))
        assertNull(cache.read("c1", listOf(member), mapOf(row.id to row.copy(savedUri = "content://other")), emptySet()))
        assertNull(cache.read("c1", listOf(member), mapOf(row.id to row), setOf("file-stat:test")))
        assertNull(cache.read("c1", listOf(member.copy(identityJson = "[\"app:other\"]")), mapOf(row.id to row), emptySet()))
        assertTrue(cache.read("c1", emptyList(), mapOf(row.id to row), emptySet())!!.refs.isEmpty())
    }

    @Test fun pendingKeepsOnlyPreviouslyConfirmedSnapshotAndConfirmedAbsenceRemovesIt() = runBlocking {
        val cache = CollectionProjectionCache(app)
        cache.write("c1", listOf(member), snapshot(setOf(member.id)), emptySet(), stamp)
        assertNull(cache.read("c1", listOf(member), mapOf(row.id to row), emptySet()))
        cache.write("c1", listOf(member), snapshot(), emptySet(), stamp)
        cache.write("c1", listOf(member), snapshot(setOf(member.id)), emptySet(), stamp)
        assertEquals(ref, cache.read("c1", listOf(member), mapOf(row.id to row), emptySet())?.resolved?.get(member.id))
        cache.write("c1", listOf(member), snapshot(setOf(member.id)).copy(invalidated=setOf(member.id)), emptySet(), stamp)
        assertNull(cache.read("c1", listOf(member), mapOf(row.id to row), emptySet()))
    }

    @Test @Config(shadows = [CatalogMediaStore::class])
    fun externalCacheNeedsExactStoreGenerationAndCurrentFullPermission() = runBlocking {
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.READ_MEDIA_AUDIO)
        CatalogMediaStore.generation=22L
        val cache=CollectionProjectionCache(app)
        val external=row.copy(id="phone:audio:42",mediaType=MediaType.AUDIO,savedUri="content://media/external/audio/media/42",sourceAudioTitle="Title",sourceAudioArtist="Artist")
        val externalRef=LibraryMediaRef(external.id,setOf(external.id,"uri:${external.savedUri}"),external,null)
        val m=member.copy(identityJson=MediaOrganizationPolicy.encode(MediaOrganizationPolicy.keys(externalRef)))
        val value=AddressedCollection(listOf(externalRef),emptySet(),emptyMap(),checkedMembers=mapOf(m.id to m.identityJson),resolved=mapOf(m.id to externalRef))
        val current=cache.stamp()
        cache.write("external",listOf(m),value,emptySet(),current)
        assertEquals("Artist",cache.read("external",listOf(m),emptyMap(),emptySet(),current)?.resolved?.get(m.id)?.item?.sourceAudioArtist)
        assertNull(cache.read("external",listOf(m),emptyMap(),emptySet(),current.copy(generation="changed")))
        org.robolectric.Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.READ_MEDIA_AUDIO)
        assertNull(cache.read("external",listOf(m),emptyMap(),emptySet(),current))
    }

    @Test fun genericDiskCacheIsGuardedAtomicAndRebuildable() = runBlocking {
        val cache = PersistentProjectionCache(app, "test-projections")
        cache.write("one", "access-a", JSONObject().put("count", 42))
        assertEquals(42, PersistentProjectionCache(app, "test-projections").read("one", "access-a")?.getInt("count"))
        assertNull(cache.read("one", "access-b"))
        val file = File(app.noBackupFilesDir, "projections-v1/test-projections/${PersistentProjectionCache.digest("one")}.json")
        file.writeText("broken")
        assertNull(cache.read("one", "access-a"))
        cache.write("one", "access-a", JSONObject().put("count", 43))
        assertEquals(43, cache.read("one", "access-a")?.getInt("count"))
    }
}

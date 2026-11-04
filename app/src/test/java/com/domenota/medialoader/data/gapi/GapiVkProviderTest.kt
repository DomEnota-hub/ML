package com.domenota.medialoader.data.gapi

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.core.provider.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GapiVkProviderTest {
    private lateinit var store: GapiKeyStore
    private var clock = 1_798_761_600_000L
    private var legacyCalls = 0
    private val calls = mutableListOf<String>()
    private val link = "https://vkvideo.ru/video-1_2"
    private val good = GapiResponse(200, """{"platform":"vk","type":"video","title":"VK title","media":[{"type":"video","url":"https://vk6-11.vkuser.net/movie.mp4","quality":"720p","ext":"mp4"}]}""")
    private val legacyItem = MediaItem("legacy", "vk", MediaType.VIDEO, "legacy.mp4", link,
        qualityLabel = "720p", formatSelector = VkProvider.videoSelector(720))
    private val old = object : MediaProvider {
        override val id = "vk"
        override fun supports(url: String) = VkLinkParser.parse(url) != null
        override suspend fun resolve(url: String): List<MediaItem> { legacyCalls++; return listOf(legacyItem) }
    }

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("gapi-vk-test", 0)
        val ui = context.getSharedPreferences("gapi-vk-ui-test", 0)
        prefs.edit().clear().commit(); ui.edit().clear().commit()
        store = GapiKeyStore(prefs, ui, emptyList(), { clock })
        store.add("First", "fake-first"); store.add("Second", "fake-second")
    }
    private fun provider(reply: suspend (String) -> GapiResponse) = GapiVkProvider(old, store,
        GapiVkClient { _, key -> calls += key; reply(key) }, wait = { clock += it }, now = { clock })

    @Test fun successfulGapiIncludesMp3AndNeverCallsLegacyExtractor() = runBlocking {
        val p = provider { good }
        val items = p.resolve(link)
        assertEquals(listOf(MediaType.VIDEO, MediaType.AUDIO), items.map { it.type })
        assertTrue(items.all { p.safeDownloadUrl(it.downloadUrl) })
        assertFalse(p.safeDownloadUrl("https://evil.test/file.mp4"))
        assertTrue(p.safeDownloadUrl(link))
        assertEquals(0, legacyCalls)
    }

    @Test fun fallbackIsOnlyForFailedResolveAndNextLinkReturnsToGapi() = runBlocking {
        val p = provider { if (calls.size == 1) GapiResponse(404, "{}") else good }
        assertEquals(listOf(legacyItem), p.resolve(link))
        assertNotEquals("legacy", p.resolve(link).first().id)
        assertEquals(listOf("fake-first", "fake-first"), calls)
        assertEquals(1, legacyCalls)
    }

    @Test fun exhaustedVkKeyIsAlsoSkippedByInstagramAndCallsShareSpacing() = runBlocking {
        val coordinator = GapiRequestCoordinator()
        val vk = GapiVkProvider(old, store, GapiVkClient { _, key ->
            calls += key
            if (key == "fake-first") GapiResponse(429, """{"detail":"Monthly token balance exhausted"}""") else good
        }, wait = { clock += it }, now = { clock }, coordinator = coordinator)
        vk.resolve(link)
        assertEquals(listOf("fake-first", "fake-second"), calls)
        val igLegacy = object : MediaProvider {
            override val id = "instagram"
            override fun supports(url: String) = true
            override suspend fun resolve(url: String): List<MediaItem> = throw AssertionError("Legacy Instagram must not be used")
        }
        val instagram = GapiInstagramProvider(igLegacy, store, GapiInstagramClient { _, key ->
            calls += key
            GapiResponse(200, """{"platform":"instagram","type":"video","media":[{"type":"video","url":"https://video.fbcdn.net/movie.mp4"}]}""")
        }, wait = { clock += it }, now = { clock }, coordinator = coordinator)
        val before = clock
        instagram.resolve("https://instagram.com/p/Ab123/")
        assertEquals("fake-second", calls.last())
        assertTrue(clock - before >= 1100L)
        assertEquals(0, legacyCalls)
    }

    @Test fun revokedVkKeyMovesToNextWithoutChangingLegacySession() = runBlocking {
        provider { if (it == "fake-first") GapiResponse(401, "{}") else good }.resolve(link)
        assertEquals(listOf("fake-first", "fake-second"), calls)
        assertTrue(store.snapshot().first().invalid)
        assertEquals(0, legacyCalls)
    }

    @Test fun unsupportedApiShapesRetainLegacyQualitiesAndSelectors() = runBlocking {
        val p = provider { GapiResponse(200, """{"platform":"vk","type":"album","media":[]}""") }
        assertEquals(listOf(legacyItem), p.resolve(link))
        assertEquals(1, legacyCalls)
    }

    @Test fun embeddedPlayerKeepsLegacyWithoutConsumingQuota() = runBlocking {
        val p = provider { throw AssertionError("Embedded player must not spend GAPI quota") }
        assertEquals(listOf(legacyItem), p.resolve("https://vk.com/video_ext.php?oid=-1&id=2"))
        assertTrue(calls.isEmpty())
        assertEquals(1, legacyCalls)
    }

    @Test fun temporaryFailureRetriesOnceThenFallsBackAndNextResolveTriesGapi() = runBlocking {
        val p = provider { if (calls.size <= 2) GapiResponse(502, "{}") else good }
        assertEquals(listOf(legacyItem), p.resolve(link))
        assertNotEquals("legacy", p.resolve(link).first().id)
        assertEquals(3, calls.size)
        assertEquals(1, legacyCalls)
    }

    @Test fun missingKeysAndDisabledFallbackNeverInvokeLegacy() = runBlocking {
        store.snapshot().forEach { store.setEnabled(it.id, false) }
        store.fallbackEnabled = false
        try { provider { good }.resolve(link); fail("Expected provider failure") }
        catch (e: ProviderException) { assertEquals("vk", e.providerId); assertNotEquals(ProviderException.Reason.ACCESS_REQUIRED, e.reason) }
        assertTrue(calls.isEmpty())
        assertEquals(0, legacyCalls)
    }

    @Test fun cancellationDoesNotConsumeAnotherKeyOrFallback() = runBlocking {
        try { provider { throw CancellationException() }.resolve(link); fail("Expected cancellation") }
        catch (_: CancellationException) { }
        assertEquals(listOf("fake-first"), calls)
        assertEquals(0, legacyCalls)
    }
}

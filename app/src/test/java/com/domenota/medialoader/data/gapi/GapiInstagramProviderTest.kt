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
class GapiInstagramProviderTest {
    private lateinit var store: GapiKeyStore
    private var clock = 1_798_761_600_000L
    private var legacyCalls = 0
    private val calls = mutableListOf<String>()
    private val link = "https://www.instagram.com/reel/DcBx_I1iHZW/"
    private val good = GapiResponse(200, """{"platform":"instagram","type":"video","media":[{"type":"video","url":"https://video.cdninstagram.com/movie.mp4"}]}""")
    private val old = object : MediaProvider {
        override val id = "instagram"
        override fun supports(url: String) = true
        override suspend fun resolve(url: String): List<MediaItem> {
            legacyCalls++
            return listOf(MediaItem("legacy", id, MediaType.VIDEO, "legacy.mp4", "https://video.cdninstagram.com/legacy.mp4"))
        }
    }
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("gapi-provider-test", 0)
        val ui = context.getSharedPreferences("gapi-provider-ui", 0)
        prefs.edit().clear().commit(); ui.edit().clear().commit()
        store = GapiKeyStore(prefs, ui, emptyList(), { clock })
        store.add("First", "fake-first"); store.add("Second", "fake-second")
    }
    private fun provider(reply: suspend (String) -> GapiResponse) = GapiInstagramProvider(old, store,
        GapiInstagramClient { _, key -> calls += key; reply(key) }, wait = { clock += it }, now = { clock })

    @Test fun successNeverUsesAccountAndEveryNewLinkStartsWithApi() = runBlocking {
        val p = provider { good }
        assertNotEquals("legacy", p.resolve(link).first().id)
        p.resolve(link)
        assertEquals(listOf("fake-first", "fake-first"), calls)
        assertEquals(0, legacyCalls)
    }
    @Test fun quotaMovesToSecondAndPersistsSelectionAcrossProviderRecreation() = runBlocking {
        val p = provider { if (it == "fake-first") GapiResponse(429, """{"detail":"Monthly token balance exhausted"}""") else good }
        p.resolve(link)
        assertEquals(listOf("fake-first", "fake-second"), calls)
        assertEquals("First", store.snapshot().first { it.exhaustedMonth == store.currentMonth() }.name)
        calls.clear(); provider { good }.resolve(link)
        assertEquals(listOf("fake-second"), calls); assertEquals(0, legacyCalls)
    }
    @Test fun rateLimitWaitsForSameKeyWithoutDeclaringMonthlyExhaustion() = runBlocking {
        var count = 0
        provider { if (count++ == 0) GapiResponse(429, """{"detail":"Too many requests"}""", 2_000) else good }.resolve(link)
        assertEquals(listOf("fake-first", "fake-first"), calls)
        assertTrue(store.snapshot().all { it.exhaustedMonth == null }); assertEquals(0, legacyCalls)
    }
    @Test fun missingMediaUsesOneFallbackAndNextRequestReturnsToApi() = runBlocking {
        val p = provider { if (calls.size == 1) GapiResponse(404, "{}") else good }
        assertEquals("legacy", p.resolve(link).first().id)
        assertNotEquals("legacy", p.resolve(link).first().id)
        assertEquals(1, legacyCalls)
    }
    @Test fun revokedKeyMovesToNextWithoutLoggingOrReturningCredentials() = runBlocking {
        provider { if (it == "fake-first") GapiResponse(401, "{}") else good }.resolve(link)
        assertEquals(listOf("fake-first", "fake-second"), calls)
        assertTrue(store.snapshot().first().invalid); assertEquals(0, legacyCalls)
    }
    @Test fun disabledFallbackDoesNotAccessAccountOrAskForLogin() = runBlocking {
        store.fallbackEnabled = false
        try { provider { GapiResponse(404, "{}") }.resolve(link); fail("Expected failure") }
        catch (e: ProviderException) { assertNotEquals(ProviderException.Reason.ACCESS_REQUIRED, e.reason) }
        assertEquals(0, legacyCalls)
    }
    @Test fun temporaryFailureRetriesOnlyOnceThenUsesOneFallback() = runBlocking {
        assertEquals("legacy", provider { GapiResponse(502, "{}") }.resolve(link).first().id)
        assertEquals(listOf("fake-first", "fake-first"), calls)
        assertEquals(1, legacyCalls)
    }
    @Test fun cancellationDoesNotRetryOrAccessAccount() = runBlocking {
        try { provider { throw CancellationException() }.resolve(link); fail("Expected cancellation") }
        catch (_: CancellationException) { }
        assertEquals(1, calls.size); assertEquals(0, legacyCalls)
    }
}

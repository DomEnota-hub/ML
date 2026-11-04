package com.domenota.medialoader.core.provider

import com.domenota.medialoader.core.model.MediaType
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GapiInstagramClientTest {
    private val page = "https://instagram.com/p/Ab_12-z/?igsh=abc"

    @Test fun mapsAlbumWithStableIdentityCaptionAndPlayableVideo() = runBlocking {
        val client = GapiInstagramClient { path, key ->
            assertEquals("/v2/instagram/media?url=https%3A%2F%2Finstagram.com%2Fp%2FAb_12-z%2F%3Figsh%3Dabc", path)
            assertEquals("test-key", key)
            GapiResponse(200, """{"platform":"instagram","type":"album","title":"Лето #море, снова https://example.org","author":null,"thumbnail":"https://scontent.fbcdn.net/cover.jpg","duration":null,"media":[{"type":"image","url":"https://scontent.cdninstagram.com/a.jpg","quality":null,"ext":null},{"type":"video","url":"https://video.fbcdn.net/b.mp4","quality":"1080p","ext":"mp4"}]}""")
        }
        val items = client.resolve(page, "test-key")
        assertEquals(listOf("Ab_12-z_1", "Ab_12-z_2"), items.map { it.id })
        assertEquals(listOf("Лето, снова_1.jpg", "Лето, снова_2.mp4"), items.map { it.originalName })
        assertEquals(listOf(MediaType.PHOTO, MediaType.VIDEO), items.map { it.type })
        assertEquals(listOf(1, 2), items.map { it.position })
        assertTrue(items.all { it.providerId == "instagram" && it.sourceGroupId == "Ab_12-z" && it.sourcePageUrl == page })
        assertEquals(true, items.last().audioAvailable)
        assertEquals("1080p", items.last().qualityLabel)
        assertEquals("https://scontent.fbcdn.net/cover.jpg", items.last().previewUrl)
        assertEquals("Лето, снова_2.mp3", DefaultMediaResolver.audioTrackOf(items.last()).originalName)
    }

    @Test fun absentOptionalMetadataNeedsNoEnrichmentAndUsesSafeFallback() = runBlocking {
        var calls = 0
        val item = GapiInstagramClient { _, _ ->
            calls++
            GapiResponse(200, """{"platform":"instagram","type":"video","title":null,"author":null,"thumbnail":null,"duration":null,"media":[{"type":"video","url":"https://video.fbcdn.net/clip.mp4","quality":null,"ext":null}]}""")
        }.resolve(page, "test-key").single()
        assertEquals("Instagram.mp4", item.originalName)
        assertEquals("Ab_12-z", item.id)
        assertNull(item.position)
        assertNull(item.previewUrl)
        assertEquals(1, calls)
    }

    @Test fun rejectsUntrustedDownloadHostsAndUnsafeThumbnails() = runBlocking {
        for (url in listOf("https://fbcdn.net.evil.test/a.mp4", "http://scontent.fbcdn.net/a.mp4", "https://user:password@video.fbcdn.net/a.mp4", "file:///tmp/a.mp4", "https://www.instagram.com/a.mp4")) {
            val error = failure(GapiResponse(200, """{"platform":"instagram","type":"video","media":[{"type":"video","url":"$url"}]}"""))
            assertEquals(GapiFailureKind.UNSUPPORTED, error.kind)
        }
        val item = GapiInstagramClient { _, _ -> GapiResponse(200,
            """{"platform":"instagram","type":"image","thumbnail":"https://evil.test/a.jpg","media":[{"type":"image","url":"https://scontent.fbcdn.net/a.jpg"}]}""")
        }.resolve(page, "test-key").single()
        assertEquals("https://scontent.fbcdn.net/a.jpg", item.previewUrl)
    }

    @Test fun classifiesFailuresWithoutLeakingResponseOrKey() = runBlocking {
        val cases = listOf(
            GapiResponse(401, "test-key invalid") to GapiFailureKind.INVALID_KEY,
            GapiResponse(429, """{"detail":"Too many requests"}""", 3000) to GapiFailureKind.RATE_LIMITED,
            GapiResponse(429, """{"detail":"Quota exceeded"}""") to GapiFailureKind.RATE_LIMITED,
            GapiResponse(429, """{"detail":"Monthly quota exhausted"}""") to GapiFailureKind.QUOTA_EXHAUSTED,
            GapiResponse(429, """{"detail":"Monthly quota exhausted"}""", 3000) to GapiFailureKind.QUOTA_EXHAUSTED,
            GapiResponse(402, """{"detail":"Token balance exhausted"}""") to GapiFailureKind.QUOTA_EXHAUSTED,
            GapiResponse(502, "upstream test-key") to GapiFailureKind.TEMPORARY,
            GapiResponse(400, "unsupported test-key") to GapiFailureKind.UNSUPPORTED,
            GapiResponse(404, "missing test-key") to GapiFailureKind.NOT_FOUND,
        )
        for ((response, expected) in cases) {
            val error = failure(response)
            assertEquals(expected, error.kind)
            assertEquals(response.retryAfterMillis, error.retryAfterMillis)
            assertFalse(error.toString().contains("test-key"))
            assertNull(error.cause)
            assertFalse(response.toString().contains("test-key"))
        }
    }

    @Test fun networkFailureIsTemporaryAndDoesNotRetry() = runBlocking {
        var calls = 0
        val error = expectFailure {
            GapiInstagramClient { _, _ -> calls++; throw IOException("secret-key") }.resolve(page, "secret-key")
        }
        assertEquals(GapiFailureKind.TEMPORARY, error.kind)
        assertFalse(error.toString().contains("secret-key"))
        assertEquals(1, calls)
    }

    @Test fun rateLimitDoesNotInferMonthlyExhaustionFromBalanceMetadata() = runBlocking {
        val error = failure(GapiResponse(429,
            """{"detail":"Too many requests","monthly_tokens_remaining":0}""", 3000))
        assertEquals(GapiFailureKind.RATE_LIMITED, error.kind)
        assertEquals(3000L, error.retryAfterMillis)
    }

    @Test fun missingPublicationKeepsItsStatusRegardlessOfQuotaMetadata() = runBlocking {
        val error = failure(GapiResponse(404,
            """{"detail":"Publication not found","metadata":{"monthly_quota":"exhausted"}}"""))
        assertEquals(GapiFailureKind.NOT_FOUND, error.kind)
    }

    @Test fun quotaTextOnlyOverridesExpectedQuotaStatuses() = runBlocking {
        assertEquals(GapiFailureKind.NOT_FOUND,
            failure(GapiResponse(404, """{"detail":"Monthly quota exhausted"}""")).kind)
        assertEquals(GapiFailureKind.TEMPORARY,
            failure(GapiResponse(502, """{"message":"Token balance exhausted"}""")).kind)
        assertEquals(GapiFailureKind.QUOTA_EXHAUSTED,
            failure(GapiResponse(402, """{"message":"Token balance exhausted"}""")).kind)
    }

    @Test fun propagatesCancellation() = runBlocking {
        val cancellation = CancellationException("cancelled")
        try {
            GapiInstagramClient { _, _ -> throw cancellation }.resolve(page, "test-key")
            fail("Cancellation must propagate")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
    }

    @Test fun selectsOnlyRequestedStoryWhenResponseContainsIds() = runBlocking {
        val storyPage = "https://instagram.com/stories/some.user/123456789/"
        val items = GapiInstagramClient { path, _ ->
            assertEquals("/v2/instagram/stories?username=some.user", path)
            GapiResponse(200, """{"platform":"instagram","stories":[{"id":"987654321","type":"image","url":"https://scontent.fbcdn.net/other.jpg"},{"id":"123456789","type":"video","url":"https://video.fbcdn.net/requested.mp4"}]}""")
        }.resolve(storyPage, "test-key")
        assertEquals(1, items.size)
        assertEquals("https://video.fbcdn.net/requested.mp4", items.single().downloadUrl)
        assertEquals("123456789", items.single().id)
        assertEquals(storyPage, items.single().sourcePageUrl)
    }

    @Test fun unidentifiedStoryCollectionCannotReplaceRequestedStory() = runBlocking {
        val error = expectFailure {
            GapiInstagramClient { _, _ -> GapiResponse(200,
                """{"platform":"instagram","type":"album","media":[{"type":"image","url":"https://scontent.fbcdn.net/a.jpg"},{"type":"image","url":"https://scontent.fbcdn.net/b.jpg"}]}""")
            }.resolve("https://instagram.com/stories/user/123456789/", "test-key")
        }
        assertEquals(GapiFailureKind.UNSUPPORTED, error.kind)
    }

    @Test fun absentRequestedStoryDoesNotReturnAnotherActiveStory() = runBlocking {
        val error = expectFailure {
            GapiInstagramClient { _, _ -> GapiResponse(200,
                """{"platform":"instagram","stories":[{"id":"987654321","type":"image","url":"https://scontent.fbcdn.net/a.jpg"}]}""")
            }.resolve("https://instagram.com/stories/user/123456789/", "test-key")
        }
        assertEquals(GapiFailureKind.NOT_FOUND, error.kind)
    }

    @Test fun storyIdOnCollectionCannotAuthorizeOtherStories() = runBlocking {
        val error = expectFailure {
            GapiInstagramClient { _, _ -> GapiResponse(200,
                """{"platform":"instagram","id":"123456789","type":"album","media":[{"type":"image","url":"https://scontent.fbcdn.net/a.jpg"},{"type":"image","url":"https://scontent.fbcdn.net/b.jpg"}]}""")
            }.resolve("https://instagram.com/stories/user/123456789/", "test-key")
        }
        assertEquals(GapiFailureKind.UNSUPPORTED, error.kind)
    }

    @Test fun boundedBodyCannotConsumeAnUnlimitedResponse() = runBlocking {
        assertEquals(GapiFailureKind.TEMPORARY, failure(GapiResponse(200, "x".repeat(1_048_577))).kind)
    }

    @Test fun malformedKeyIsRejectedBeforeTransport() = runBlocking {
        val error = expectFailure {
            GapiInstagramClient { _, _ -> error("Transport must not run") }.resolve(page, "key\r\nCookie: secret")
        }
        assertEquals(GapiFailureKind.INVALID_KEY, error.kind)
    }

    @Test fun rejectsForeignSourceBeforeSendingKey() = runBlocking {
        val error = expectFailure {
            GapiInstagramClient { _, _ -> error("Transport must not run") }
                .resolve("https://evil.test/p/Ab_12-z/", "test-key")
        }
        assertEquals(GapiFailureKind.UNSUPPORTED, error.kind)
    }

    private suspend fun failure(response: GapiResponse): GapiException = expectFailure {
        GapiInstagramClient { _, _ -> response }.resolve(page, "test-key")
    }

    @Test fun savedProfileExclusionsStillSelectSameAlbumFilesAfterGapiSwitch() = runBlocking {
        val old = InstagramProvider.parseApiResponse("""{"items":[{"carousel_media":[{"image_versions2":{"candidates":[{"url":"https://scontent.fbcdn.net/a.jpg"}]}},{"video_versions":[{"url":"https://video.fbcdn.net/b.mp4"}]}]}]}""", "Ab_12-z")
        val fresh = GapiInstagramClient { _, _ -> GapiResponse(200,
            """{"platform":"instagram","type":"album","media":[{"type":"image","url":"https://scontent.fbcdn.net/a.jpg"},{"type":"video","url":"https://video.fbcdn.net/b.mp4"}]}""")
        }.resolve(page, "test-key")
        assertEquals(old.map { it.id }, fresh.map { it.id })
        val selected = com.domenota.medialoader.data.instagramprofile.InstagramProfileScanPolicy.selectedVisuals(fresh, setOf(old.first().id))
        assertEquals(old.drop(1).map { it.id }, selected.map { it.id })
    }

    private suspend fun expectFailure(block: suspend () -> Unit): GapiException {
        try { block() } catch (error: GapiException) { return error }
        throw AssertionError("Expected GAPI failure")
    }
}

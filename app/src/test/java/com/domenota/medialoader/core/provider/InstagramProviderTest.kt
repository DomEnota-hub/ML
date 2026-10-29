package com.domenota.medialoader.core.provider

import com.domenota.medialoader.core.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramProviderTest {
    private val photoMeta = """<meta property="og:image" content="https://scontent.fbcdn.net/p.jpg">"""
    private val blockedPage = "<html>Log in</html>"

    private fun expectProviderError(block: suspend () -> Any?): ProviderException {
        try {
            runBlocking { block() }
        } catch (error: ProviderException) {
            return error
        }
        throw AssertionError("Expected ProviderException")
    }

    // ---- public access: post, reel, wrong link -------------------------------------------------

    @Test fun plainPostWithPhoto() {
        runBlocking {
            val item = InstagramProvider { photoMeta }.resolve("https://instagram.com/p/Ab_12-z/").single()
            assertEquals(MediaType.PHOTO, item.type)
            assertEquals("p.jpg", item.originalName)
            assertEquals("https://scontent.fbcdn.net/p.jpg", item.downloadUrl)
        }
    }

    @Test fun reelUsesReelPageAndReturnsVideo() {
        runBlocking {
            val requested = mutableListOf<String>()
            val page = """<meta content="https://scontent.fbcdn.net/video.mp4?a=1&amp;b=2" property="og:video" /><meta property="og:image" content="https://scontent.fbcdn.net/cover.jpg">"""
            val provider = InstagramProvider { url -> requested += url; page }
            val item = provider.resolve("https://www.instagram.com/reel/Ab_12-z/?igsh=x").single()
            assertEquals(
                listOf(
                    "https://www.instagram.com/reel/Ab_12-z/",
                    "https://www.instagram.com/reel/Ab_12-z/embed/captioned/",
                ), requested,
            )
            assertEquals(MediaType.VIDEO, item.type)
            assertEquals("https://scontent.fbcdn.net/video.mp4?a=1&b=2", item.downloadUrl)
            assertEquals("Instagram.mp4", item.originalName)
        }
    }

    @Test fun postAndTvKeepTheirOwnPages() {
        runBlocking {
            val requested = mutableListOf<String>()
            val provider = InstagramProvider { url -> requested += url; photoMeta }
            provider.resolve("https://www.instagram.com/p/Ab_12-z/")
            provider.resolve("https://www.instagram.com/tv/Ab_12-z/")
            assertEquals(
                listOf("https://www.instagram.com/p/Ab_12-z/", "https://www.instagram.com/tv/Ab_12-z/"),
                requested,
            )
        }
    }

    @Test fun reelEmbedFallbackKeepsReelKindAndDecodesEscapedUrl() {
        runBlocking {
            val requested = mutableListOf<String>()
            val provider = InstagramProvider { url ->
                requested += url
                if (url.endsWith("/embed/captioned/"))
                    """<script>{"video_url":"https:\/\/scontent.fbcdn.net\/clip.mp4?a=1\u0026b=2","display_url":"https:\/\/scontent.fbcdn.net\/cover.jpg"}</script>"""
                else "<html>No public media metadata</html>"
            }
            val item = provider.resolve("https://www.instagram.com/reel/Ab_12-z/").single()
            assertEquals("https://www.instagram.com/reel/Ab_12-z/embed/captioned/", requested.last())
            assertEquals(MediaType.VIDEO, item.type)
            assertEquals("https://scontent.fbcdn.net/clip.mp4?a=1&b=2", item.downloadUrl)
            assertEquals("https://scontent.fbcdn.net/cover.jpg", item.previewUrl)
        }
    }

    @Test fun embedOnlyReelRecoversCaptionAndRemovesAllHashtags() = runBlocking {
        val provider = InstagramProvider { url ->
            if (url.endsWith("/embed/captioned/")) {
                """<meta property="og:description" content="purpleegg on Instagram: Твои выразительные глаза 👀 #аниме #reels #пример_2026"><script>{"video_url":"https:\/\/scontent.fbcdn.net\/clip.mp4"}</script>"""
            } else "<html>No public media metadata</html>"
        }
        val result = provider.resolve("https://www.instagram.com/reel/Ab_12-z/").single()
        assertEquals("Твои выразительные глаза 👀.mp4", result.originalName)
    }

    @Test fun hashtagOnlyEmbedKeepsFallbackAndDoesNotInventCaption() = runBlocking {
        val provider = InstagramProvider { url ->
            if (url.endsWith("/embed/captioned/")) {
                """<meta property="og:description" content="#аниме #reels #мемы"><script>{"video_url":"https:\/\/scontent.fbcdn.net\/clip.mp4"}</script>"""
            } else "<html>No public media metadata</html>"
        }
        assertEquals("Instagram.mp4", provider.resolve("https://www.instagram.com/reel/Ab_12-z/").single().originalName)
    }

    @Test fun genericCdnVideoNameIsReplacedWhenEmbedHasVerifiedCaption() = runBlocking {
        val provider = InstagramProvider { url ->
            if (url.endsWith("/embed/captioned/")) {
                """<meta property="og:description" content="purpleegg on Instagram: Твои выразительные глаза 👀 #аниме"><script>{"video_url":"https:\/\/scontent.fbcdn.net\/video.mp4"}</script>"""
            } else """<meta property="og:video" content="https://scontent.fbcdn.net/video.mp4">"""
        }
        assertEquals("Твои выразительные глаза 👀.mp4",
            provider.resolve("https://www.instagram.com/reel/Ab_12-z/").single().originalName)
    }

    @Test fun wrongLinkIsUnsupportedAndNeverHitsTheNetwork() {
        var requests = 0
        val provider = InstagramProvider { requests++; "<html/>" }
        assertFalse(provider.supports("https://www.instagram.com/accounts/login/"))
        val error = expectProviderError { provider.resolve("https://www.instagram.com/accounts/login/") }
        assertEquals(ProviderException.Reason.UNSUPPORTED, error.reason)
        assertEquals(0, requests)
    }

    @Test fun resolverRejectsForeignLinks() {
        val resolver = DefaultMediaResolver(listOf(InstagramProvider { "<html/>" }))
        val error = expectProviderError { resolver.resolve("https://example.com/video/1") }
        assertEquals(ProviderException.Reason.UNSUPPORTED, error.reason)
    }

    // ---- carousels and file names -------------------------------------------------------------

    private fun carouselPage(firstUrl: String, secondUrl: String, secondCover: String): String {
        val id = InstagramProvider.mediaId("Ab_12-z")
        return """<script type="application/json" data-sjs>{"payload":{"item":{"if_not_gated_logged_out":{"pk":"$id","carousel_media":[{"image_versions2":{"candidates":[{"url":"https://scontent.fbcdn.net/small.jpg","width":640},{"url":"$firstUrl","width":1080}]}},{"video_versions":[{"url":"$secondUrl","width":720}],"image_versions2":{"candidates":[{"url":"$secondCover","width":400}]}}]}}}}</script>"""
    }

    @Test fun photoCarouselIsNumberedFromTheFirstItem() {
        runBlocking {
            val id = InstagramProvider.mediaId("Ab_12-z")
            val page = """<script data-sjs>{"item":{"pk":"$id","carousel_media":[{"image_versions2":{"candidates":[{"url":"https://scontent.fbcdn.net/a.jpg","width":1080}]}},{"image_versions2":{"candidates":[{"url":"https://scontent.fbcdn.net/b.jpg","width":1080}]}},{"image_versions2":{"candidates":[{"url":"https://scontent.fbcdn.net/c.jpg","width":1080}]}}]}}</script>"""
            val items = InstagramProvider { page }.resolve("https://instagram.com/p/Ab_12-z/")
            assertEquals(listOf("a_1.jpg", "b_2.jpg", "c_3.jpg"), items.map { it.originalName })
            assertEquals(3, items.map { it.id }.toSet().size)
        }
    }

    @Test fun mixedCarouselKeepsOrderTypesAndNumbers() {
        runBlocking {
            val page = carouselPage(
                "https://scontent.fbcdn.net/photoA_hd.jpg",
                "https://scontent.fbcdn.net/movie.mp4",
                "https://scontent.fbcdn.net/cover.jpg",
            )
            val items = InstagramProvider { page }.resolve("https://instagram.com/p/Ab_12-z/")
            assertEquals(listOf(MediaType.PHOTO, MediaType.VIDEO), items.map { it.type })
            assertEquals("https://scontent.fbcdn.net/photoA_hd.jpg", items[0].downloadUrl)
            assertEquals("https://scontent.fbcdn.net/cover.jpg", items[1].previewUrl)
            assertEquals(listOf("photoA_hd_1.jpg", "movie_2.mp4"), items.map { it.originalName })
        }
    }

    @Test fun namelessMediaFallsBackToInstagramNamesNeverInstagramCode() {
        runBlocking {
            val single = InstagramProvider { """<meta property="og:image" content="https://scontent.fbcdn.net/">""" }
                .resolve("https://instagram.com/p/Ab_12-z/").single()
            assertEquals("Instagram.jpg", single.originalName)

            val video = InstagramProvider { """<meta property="og:video" content="https://scontent.fbcdn.net/">""" }
                .resolve("https://instagram.com/reel/Ab_12-z/").single()
            assertEquals("Instagram.mp4", video.originalName)

            val page = carouselPage("https://scontent.fbcdn.net/", "https://scontent.fbcdn.net/", "https://scontent.fbcdn.net/")
            val items = InstagramProvider { page }.resolve("https://instagram.com/p/Ab_12-z/")
            assertEquals(listOf("Instagram_1.jpg", "Instagram_2.mp4"), items.map { it.originalName })
            assertTrue(items.none { it.originalName.startsWith("instagram_") })
        }
    }

    @Test fun metaCaptionNamesVideoWithoutAuthorAndHashtags() {
        runBlocking {
            val page = """<meta property="og:video" content="https://scontent.fbcdn.net/clip.mp4"><meta property="og:description" content="2,305 likes, 21 comments - purpleegg on September 2: &quot;Ты уникален #аниме #мем&quot;">"""
            val item = InstagramProvider { page }.resolve("https://instagram.com/reel/Ab_12-z/").single()
            assertEquals("Ты уникален.mp4", item.originalName)
        }
    }

    @Test fun titleMetaCanSupplyCaptionWhenDescriptionIsMissing() {
        runBlocking {
            val page = """<meta property="og:image" content="https://scontent.fbcdn.net/photo.jpg"><meta property="og:title" content="purpleegg on Instagram: Последняя неделя лета #аниме #мем">"""
            val item = InstagramProvider { page }.resolve("https://instagram.com/p/Ab_12-z/").single()
            assertEquals("Последняя неделя лета.jpg", item.originalName)
        }
    }

    @Test fun embeddedCarouselUsesCaptionForEveryVisualItem() {
        runBlocking {
            val id = InstagramProvider.mediaId("Ab_12-z")
            val page = """<script data-sjs>{"item":{"pk":"$id","user":{"username":"purpleegg"},"caption":{"text":"@purpleegg Последняя неделя лета #аниме #мем"},"carousel_media":[{"image_versions2":{"candidates":[{"url":"https://scontent.fbcdn.net/a.jpg"}]}},{"video_versions":[{"url":"https://scontent.fbcdn.net/b.mp4"}]}]}}</script>"""
            val items = InstagramProvider { page }.resolve("https://instagram.com/p/Ab_12-z/")
            assertEquals(listOf("Последняя неделя лета_1.jpg", "Последняя неделя лета_2.mp4"), items.map { it.originalName })
        }
    }

    @Test fun authorizedCaptionNamesVideoAndDerivedAudio() {
        val body = """{"items":[{"caption":{"text":"Ты уникален #аниме #мем"},"video_versions":[{"url":"https://scontent.cdninstagram.com/clip.mp4"}]}]}"""
        val items = runBlocking {
            val provider = InstagramProvider(
                cookies = { "sessionid=1" },
                apiClient = InstagramApiClient { _, _ -> body },
            ) { "<html>Log in</html>" }
            DefaultMediaResolver(listOf(provider)).resolve("https://instagram.com/reel/Ab_12-z/")
        }
        assertEquals(listOf("Ты уникален.mp4", "Ты уникален.mp3"), items.map { it.originalName })
    }

    @Test fun hashtagOnlyCaptionKeepsSafeFallbackName() {
        val product = org.json.JSONObject("""{"caption":{"text":"#аниме #мем"},"video_versions":[{"url":"https://scontent.fbcdn.net/"}]}""")
        assertEquals("Instagram.mp4", InstagramProvider.itemsFromProduct(product, "Ab_12-z").single().originalName)
    }

    @Test fun numericCdnNamesAreReplacedAndSuppliedNamesAreKept() {
        runBlocking {
            val id = InstagramProvider.mediaId("Ab_12-z")
            val page = """<script data-sjs>{"item":{"pk":"$id","carousel_media":[{"image_versions2":{"candidates":[{"url":"https://scontent.fbcdn.net/803925802_1812008545991362.jpg"}]}},{"original_filename":"Trip photo.jpg","image_versions2":{"candidates":[{"url":"https://scontent.fbcdn.net/802810944_1812008547791362.jpg"}]}}]}}</script>"""
            val items = InstagramProvider { page }.resolve("https://instagram.com/p/Ab_12-z/")
            assertEquals(listOf("Instagram_1.jpg", "Trip photo_2.jpg"), items.map { it.originalName })
        }
    }

    // ---- no session, ACCESS_REQUIRED, temporary failures --------------------------------------

    @Test fun publicLinkWithoutSignInAsksForAccess() {
        val error = expectProviderError { InstagramProvider { blockedPage }.resolve("https://instagram.com/p/Ab_12-z/") }
        assertEquals(ProviderException.Reason.ACCESS_REQUIRED, error.reason)
    }

    @Test fun unreachablePagesAreATemporaryFailureNotAnAccessError() {
        val provider = InstagramProvider { throw IllegalStateException("HTTP 429") }
        val error = expectProviderError { provider.resolve("https://instagram.com/p/Ab_12-z/") }
        assertEquals(ProviderException.Reason.TEMPORARY_FAILURE, error.reason)
    }

    @Test fun loginRedirectIsNotReportedAsNetworkFailure() {
        val provider = InstagramProvider { throw ProviderException(
            ProviderException.Reason.ACCESS_REQUIRED, "login redirect", "instagram",
        ) }
        val error = expectProviderError { provider.resolve("https://instagram.com/p/Ab_12-z/") }
        assertEquals(ProviderException.Reason.ACCESS_REQUIRED, error.reason)
    }

    @Test fun ignoresUnrelatedProductsAndUnsafeUrls() {
        val page = """<script data-sjs>{"item":{"code":"Other9","image_versions2":{"candidates":[{"url":"https://scontent.fbcdn.net/wrong.jpg"}]}}}</script>"""
        assertTrue(InstagramProvider.extractEmbeddedMedia(page, "Ab_12-z").isEmpty())
        assertNull(InstagramProvider.extractEmbedMedia("""{"video_url":"https://fbcdn.net.evil.test/clip.mp4"}""", "Ab_12-z"))
    }

    @Test fun rejectsUntrustedMediaHosts() {
        assertFalse(InstagramProvider.safeMediaUrl("https://fbcdn.net.evil.test/a.jpg"))
        assertFalse(InstagramProvider.safeMediaUrl("https://cdninstagram.com.evil.test/a.mp4"))
        assertFalse(InstagramProvider.safeMediaUrl("http://scontent.fbcdn.net/a.jpg"))
        assertTrue(InstagramProvider.safeMediaUrl("https://scontent.fbcdn.net/a.jpg"))
        assertTrue(InstagramProvider.safeMediaUrl("https://scontent-waw2-1.cdninstagram.com/v/a.mp4"))
    }

    // ---- authorized fallback -------------------------------------------------------------------

    private val carouselJson = """{"items":[{"carousel_media":[{"image_versions2":{"candidates":[{"url":"https://scontent.cdninstagram.com/a.jpg","width":1080}]}},{"video_versions":[{"url":"https://scontent.cdninstagram.com/b.mp4","width":720}]}]}]}"""

    @Test fun publicAttemptComesBeforeSession() {
        runBlocking {
            var apiCalls = 0
            val provider = InstagramProvider(
                cookies = { "sessionid=1" },
                apiClient = InstagramApiClient { _, _ -> apiCalls++; "{}" },
            ) { photoMeta }
            assertEquals(MediaType.PHOTO, provider.resolve("https://instagram.com/p/Ab_12-z/").single().type)
            assertEquals(0, apiCalls)
        }
    }

    @Test fun sessionFallbackRunsOnlyAfterBothPublicPagesFail() {
        runBlocking {
            val events = mutableListOf<String>()
            val provider = InstagramProvider(
                cookies = { "sessionid=1; ds_user_id=2" },
                apiClient = InstagramApiClient { url, cookies -> events += "api $url | $cookies"; carouselJson },
            ) { url -> events += "page $url"; blockedPage }
            val items = provider.resolve("https://instagram.com/reel/Ab_12-z/")
            val mediaId = InstagramProvider.mediaId("Ab_12-z")
            assertEquals(
                listOf(
                    "page https://www.instagram.com/reel/Ab_12-z/",
                    "page https://www.instagram.com/reel/Ab_12-z/embed/captioned/",
                    "api https://www.instagram.com/api/v1/media/$mediaId/info/ | sessionid=1; ds_user_id=2",
                ),
                events,
            )
            assertEquals(listOf(MediaType.PHOTO, MediaType.VIDEO), items.map { it.type })
            assertEquals(listOf("a_1.jpg", "b_2.mp4"), items.map { it.originalName })
        }
    }

    @Test fun rateLimitStopsPublicFallbackAndAvoidsSessionRequest() {
        var pageCalls = 0
        var apiCalls = 0
        val provider = InstagramProvider(
            cookies = { "sessionid=1" },
            apiClient = InstagramApiClient { _, _ -> apiCalls++; "{}" },
        ) {
            pageCalls++
            throw ProviderException(ProviderException.Reason.RATE_LIMITED,
                "Instagram ограничил запросы", "instagram")
        }
        val error = expectProviderError { provider.resolve("https://instagram.com/p/Ab_12-z/") }
        assertEquals(ProviderException.Reason.RATE_LIMITED, error.reason)
        assertEquals(1, pageCalls)
        assertEquals(0, apiCalls)
    }

    @Test fun challengeInApiResponseIsReportedAsRateLimit() {
        val error = expectProviderError {
            InstagramProvider.parseApiResponse("""{"status":"fail","message":"challenge_required"}""", "post")
        }
        assertEquals(ProviderException.Reason.RATE_LIMITED, error.reason)
    }

    @Test fun expiredSessionAsksToSignInAgain() {
        val provider = InstagramProvider(
            cookies = { "sessionid=1" },
            apiClient = InstagramApiClient { _, _ -> "<html>login</html>" },
        ) { "<html/>" }
        val error = expectProviderError { provider.resolve("https://instagram.com/p/Ab_12-z/") }
        assertEquals(ProviderException.Reason.ACCESS_REQUIRED, error.reason)
    }

    // ---- Stories -------------------------------------------------------------------------------

    @Test fun storiesAlwaysRequireSessionAndSkipPublicPages() {
        var pageRequests = 0
        val provider = InstagramProvider { pageRequests++; "<html/>" }
        val error = expectProviderError { provider.resolve("https://www.instagram.com/stories/user/3456789012345/") }
        assertEquals(ProviderException.Reason.ACCESS_REQUIRED, error.reason)
        assertEquals(0, pageRequests)
    }

    @Test fun storyIsResolvedThroughApiWithSession() {
        runBlocking {
            val body = """{"items":[{"media_type":2,"video_versions":[{"url":"https://scontent.cdninstagram.com/story.mp4","width":720}],"image_versions2":{"candidates":[{"url":"https://scontent.cdninstagram.com/story.jpg","width":720}]}}]}"""
            var seenUrl: String? = null
            var seenCookies: String? = null
            val provider = InstagramProvider(
                cookies = { "sessionid=1; ds_user_id=2" },
                apiClient = InstagramApiClient { url, cookies -> seenUrl = url; seenCookies = cookies; body },
            )
            val item = provider.resolve("https://www.instagram.com/stories/user/3456789012345/").single()
            assertEquals("https://www.instagram.com/api/v1/media/3456789012345/info/", seenUrl)
            assertEquals("sessionid=1; ds_user_id=2", seenCookies)
            assertEquals(MediaType.VIDEO, item.type)
            assertEquals("story.mp4", item.originalName)
        }
    }

    // ---- direct Instagram music assets -----------------------------------------------------------

    @Test fun photoWithMusicReturnsPhotoAndDirectMusic() {
        runBlocking {
            val id = InstagramProvider.mediaId("Ab_12-z")
            val page = """<script data-sjs>{"item":{"pk":"$id","image_versions2":{"candidates":[{"url":"https://scontent.fbcdn.net/photo.jpg","width":1080}]},"music_metadata":{"music_info":{"music_asset_info":{"title":"Night Drive","display_artist":"Artist","cover_artwork_uri":"https://scontent.cdninstagram.com/music-cover.jpg","progressive_download_url":"https://video.xx.fbcdn.net/night-drive.m4a"}}}}}</script>"""
            val items = InstagramProvider { page }.resolve("https://instagram.com/p/Ab_12-z/")
            assertEquals(listOf(MediaType.PHOTO, MediaType.AUDIO), items.map { it.type })
            assertEquals("https://video.xx.fbcdn.net/night-drive.m4a", items[1].downloadUrl)
            assertEquals("Artist - Night Drive.mp3", items[1].originalName)
            assertEquals("Artist", items[1].audioArtist)
            assertEquals("Night Drive", items[1].audioTitle)
            assertEquals("https://scontent.cdninstagram.com/music-cover.jpg", items[1].audioArtworkUrl)
            assertEquals("https://scontent.fbcdn.net/photo.jpg", items[1].previewUrl)
        }
    }

    @Test fun carouselGetsOnePublicationMusicTrack() {
        runBlocking {
            val id = InstagramProvider.mediaId("Ab_12-z")
            val page = """<script data-sjs>{"item":{"pk":"$id","carousel_media":[{"image_versions2":{"candidates":[{"url":"https://scontent.fbcdn.net/a.jpg","width":1080}]}},{"image_versions2":{"candidates":[{"url":"https://scontent.fbcdn.net/b.jpg","width":1080}]}}],"music_metadata":{"music_info":{"music_asset_info":{"progressive_download_url":"https://video.xx.fbcdn.net/song.m4a"}}}}}</script>"""
            val items = InstagramProvider { page }.resolve("https://instagram.com/p/Ab_12-z/")
            assertEquals(listOf(MediaType.PHOTO, MediaType.PHOTO, MediaType.AUDIO), items.map { it.type })
            assertEquals(1, items.count { it.type == MediaType.AUDIO })
            assertEquals("https://scontent.fbcdn.net/a.jpg", items.last().previewUrl)
        }
    }

    @Test fun storyMusicStickerShapeIsSupported() {
        val body = """{"items":[{"image_versions2":{"candidates":[{"url":"https://scontent.cdninstagram.com/story.jpg","width":1080}]},"story_music_stickers":[{"music_asset_info":{"title":"Story Song","progressive_download_url":"https://video.xx.fbcdn.net/story.m4a"}}]}]}"""
        val items = InstagramProvider.parseApiResponse(body, "3456789012345")
        assertEquals(listOf(MediaType.PHOTO, MediaType.AUDIO), items.map { it.type })
        assertEquals("Story Song.mp3", items[1].originalName)
    }

    @Test fun resolverPrefersDirectMusicAndDoesNotDuplicateVideoAudio() {
        runBlocking {
            val id = InstagramProvider.mediaId("Ab_12-z")
            val page = """<script data-sjs>{"item":{"pk":"$id","has_audio":true,"video_versions":[{"url":"https://scontent.fbcdn.net/clip.mp4","width":1080}],"image_versions2":{"candidates":[{"url":"https://scontent.fbcdn.net/cover.jpg","width":1080}]},"clips_metadata":{"music_info":{"music_asset_info":{"progressive_download_url":"https://video.xx.fbcdn.net/music.m4a"}}}}}</script>"""
            val items = DefaultMediaResolver(listOf(InstagramProvider { page }))
                .resolve("https://instagram.com/reel/Ab_12-z/")
            assertEquals(listOf(MediaType.VIDEO, MediaType.AUDIO), items.map { it.type })
            assertEquals("https://video.xx.fbcdn.net/music.m4a", items[1].downloadUrl)
        }
    }

    @Test fun unsafeMusicAssetIsIgnored() {
        runBlocking {
            val id = InstagramProvider.mediaId("Ab_12-z")
            val page = """<script data-sjs>{"item":{"pk":"$id","image_versions2":{"candidates":[{"url":"https://scontent.fbcdn.net/photo.jpg","width":1080}]},"music_metadata":{"music_info":{"music_asset_info":{"progressive_download_url":"https://fbcdn.net.evil.test/song.m4a"}}}}}</script>"""
            val items = InstagramProvider { page }.resolve("https://instagram.com/p/Ab_12-z/")
            assertEquals(listOf(MediaType.PHOTO), items.map { it.type })
        }
    }

    // ---- audio track offered for every video ---------------------------------------------------

    @Test fun resolverOffersAudioTrackForEveryVideo() {
        runBlocking {
            val page = """<meta property="og:video" content="https://scontent.fbcdn.net/clip.mp4"><meta property="og:image" content="https://scontent.fbcdn.net/c.jpg">"""
            val items = DefaultMediaResolver(listOf(InstagramProvider { page })).resolve("https://instagram.com/reel/Ab_12-z/")
            assertEquals(listOf(MediaType.VIDEO, MediaType.AUDIO), items.map { it.type })
            assertEquals("Instagram.mp3", items[1].originalName)
            assertEquals(items[0].downloadUrl, items[1].downloadUrl)
        }
    }

    @Test fun photosGetNoAudioTrack() {
        runBlocking {
            val items = DefaultMediaResolver(listOf(InstagramProvider { photoMeta })).resolve("https://instagram.com/p/Ab_12-z/")
            assertEquals(listOf(MediaType.PHOTO), items.map { it.type })
        }
    }

    @Test fun explicitlySilentVideoHasNoAudioOption() {
        runBlocking {
            val id = InstagramProvider.mediaId("Ab_12-z")
            val page = """<script data-sjs>{"item":{"pk":"$id","has_audio":false,"video_versions":[{"url":"https://scontent.fbcdn.net/silent.mp4","width":720}]}}</script>"""
            val items = DefaultMediaResolver(listOf(InstagramProvider { page }))
                .resolve("https://instagram.com/reel/Ab_12-z/")
            assertEquals(listOf(MediaType.VIDEO), items.map { it.type })
        }
    }
    @Test fun instagramMetaCaptionExactlyMatchesTheReelWithoutHashtags() {
        runBlocking {
            val page = """<meta property="og:video" content="https://scontent.fbcdn.net/clip.mp4">
                <meta property="og:description" content="114K likes, 196 comments - purpleegg on Instagram: &quot;Не понимаю, зачем кричать #аниме #мем&quot;">"""
            val item = InstagramProvider { page }
                .resolve("https://instagram.com/reel/Ab_12-z/").single()
            assertEquals("Не понимаю, зачем кричать.mp4", item.originalName)
        }
    }

    @Test fun removesHashtagsFromAnywhereNotEverythingAfterFirstHashtag() {
        val product = org.json.JSONObject("""{"caption":{"text":"Не понимаю #аниме, зачем кричать #мем!"},
            "video_versions":[{"url":"https://scontent.fbcdn.net/clip.mp4"}]}""")
        val item = InstagramProvider.itemsFromProduct(product, "Ab_12-z").single()
        assertEquals("Не понимаю, зачем кричать.mp4", item.originalName)
    }

    @Test fun recoversMissingCaptionFromEmbedWithoutChangingMediaUrl() {
        runBlocking {
            val requested = mutableListOf<String>()
            val item = InstagramProvider { url ->
                requested += url
                if (url.endsWith("/embed/captioned/")) {
                    """<meta property="og:description" content="Не понимаю, зачем кричать #аниме #мем">"""
                } else {
                    """<meta property="og:video" content="https://scontent.fbcdn.net/">"""
                }
            }.resolve("https://instagram.com/reel/Ab_12-z/").single()
            assertEquals("Не понимаю, зачем кричать.mp4", item.originalName)
            assertEquals("https://scontent.fbcdn.net/", item.downloadUrl)
            assertEquals(2, requested.size)
        }
    }

    @Test fun recoversMissingCaptionFromExistingSessionOnlyAfterEmbedFails() {
        runBlocking {
            var apiCalls = 0
            val provider = InstagramProvider(
                cookies = { "sessionid=test" },
                apiClient = InstagramApiClient { _, _ ->
                    apiCalls++
                    """{"items":[{"caption":{"text":"Не понимаю, зачем кричать #аниме"},
                        "video_versions":[{"url":"https://scontent.fbcdn.net/other.mp4"}]}]}"""
                },
            ) { url ->
                if (url.endsWith("/embed/captioned/")) "<html>No caption</html>"
                else """<meta property="og:video" content="https://scontent.fbcdn.net/">"""
            }
            val item = provider.resolve("https://instagram.com/reel/Ab_12-z/").single()
            assertEquals(1, apiCalls)
            assertEquals("Не понимаю, зачем кричать.mp4", item.originalName)
            assertEquals("https://scontent.fbcdn.net/", item.downloadUrl)
        }
    }

    @Test fun pureHashtagCaptionStillUsesSafeFallback() {
        val item = InstagramProvider.itemsFromProduct(org.json.JSONObject(
            """{"caption":{"text":"#аниме #мем #reels"},
                "video_versions":[{"url":"https://scontent.fbcdn.net/"}]}""",
        ), "Ab_12-z").single()
        assertEquals("Instagram.mp4", item.originalName)
    }

}

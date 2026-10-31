package com.domenota.medialoader.core.provider

import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.storage.StorageNaming
import java.math.BigInteger
import java.net.URI
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Instagram extraction. Order: public page → public embed → saved session (authorized API).
 * Stories are never public and always go straight to the session. The account password is never seen.
 */
class InstagramProvider(
    /** Saved session cookies or null. Used only after the public attempt fails (and always for Stories). */
    private val cookies: () -> String? = { null },
    private val apiClient: InstagramApiClient = InstagramApiClient(),
    private val pageSource: suspend (String) -> String = ::fetchInstagramPage,
) : MediaProvider {
    override val id = ID

    override fun supports(url: String): Boolean = InstagramLinkParser.parse(url) != null
    override fun safeDownloadUrl(url: String): Boolean = safeMediaUrl(url)

    override suspend fun resolve(url: String): List<MediaItem> {
        val link = InstagramLinkParser.parse(url) ?: throw ProviderException(
            ProviderException.Reason.UNSUPPORTED, "Нужна ссылка на публикацию, Reel или Stories Instagram.", ID,
        )
        return if (link.isStory) resolveStory(link) else resolvePost(link)
    }

    private suspend fun resolvePost(link: InstagramLink): List<MediaItem> {
        val code = link.id
        var loginWall = false
        // 1) Public access first, without any account. The page keeps the URL kind (p / reel / tv).
        val page = attempt({ pageSource(link.pageUrl()) }) { loginWall = true }
        if (page != null) {
            val publicItems = extractEmbeddedMedia(page, code).ifEmpty {
                listOfNotNull(mediaFromMeta(page, code))
            }
            if (publicItems.isNotEmpty()) {
                AppLog.verbose("Instagram", "Caption source · path=public-page · fallback=${publicItems.any(::isUnnamedVisual)}")
                return enrichMissingCaption(publicItems, link)
            }
        }
        val embedPage = attempt({ pageSource(link.embedUrl()) }) { loginWall = true }
        if (embedPage != null) {
            extractEmbedMedia(embedPage, code)?.let {
                AppLog.verbose("Instagram", "Caption source · path=embed · fallback=${isUnnamedVisual(it)}")
                return enrichMissingCaption(listOf(it), link, embedPage)
            }
        }
        // 2) Public access failed: use the saved session if the user already signed in.
        val session = cookies()?.takeIf { it.isNotBlank() }
        if (session != null) {
            return parseApiResponse(apiClient.mediaInfo(mediaId(code), session), code)
        }
        // 3) Otherwise ask the UI to offer sign-in.
        throw ProviderException(
            if (page == null && embedPage == null && !loginWall) ProviderException.Reason.TEMPORARY_FAILURE
            else ProviderException.Reason.ACCESS_REQUIRED,
            "Не удалось получить медиа без входа. Страница может требовать авторизации или формат ответа изменился.",
            ID,
        )
    }

    /**
     * A public Instagram response can contain working media but omit the caption.
     * Try the embed page and the already-authorized API only for unnamed visuals.
     * Never replace working CDN URLs or discard a successfully extracted video
     * simply because optional title enrichment failed.
     */
    private suspend fun enrichMissingCaption(
        items: List<MediaItem>, link: InstagramLink, suppliedEmbedHtml: String? = null,
    ): List<MediaItem> {
        if (items.none(::isUnnamedVisual)) return items
        AppLog.i("Instagram", "Publication media found without caption; trying caption fallback")
        var name: String? = null
        try {
            val embed = suppliedEmbedHtml ?: attempt({ pageSource(link.embedUrl()) }) {}
            if (embed != null) {
                name = captionStemFromMeta(embed)
                if (name != null) AppLog.verbose("Instagram", "Caption source · method=embed-metadata")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ProviderException) {
            if (error.reason == ProviderException.Reason.RATE_LIMITED) throw error
        } catch (_: Exception) {
            // Naming is optional: media extraction has already succeeded.
        }
        if (name == null) {
            val session = cookies()?.takeIf(String::isNotBlank)
            if (session != null) {
                try {
                    val root = JSONObject(apiClient.mediaInfo(mediaId(link.id), session))
                    val product = root.optJSONArray("items")?.optJSONObject(0)
                    if (product != null) {
                        name = captionStem(product)
                        if (name != null) AppLog.verbose("Instagram", "Caption source · method=authorized-api")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: ProviderException) {
                    if (error.reason == ProviderException.Reason.RATE_LIMITED) throw error
                } catch (_: Exception) {
                    // The original media remains usable when title lookup fails.
                }
            }
        }
        val stem = name ?: run {
            AppLog.w("Instagram", "Caption unavailable · reason=no-verified-description · fallback=platform")
            return items
        }
        AppLog.i("Instagram", "Publication caption recovered using fallback metadata · hashtagPolicy=strip")
        return items.map { item ->
            if (isUnnamedVisual(item)) item.copy(originalName = StorageNaming.mediaFileName(
                stem, item.position, if (item.type == MediaType.VIDEO) "mp4" else "jpg",
            )) else item
        }
    }

    private fun isUnnamedVisual(item: MediaItem): Boolean =
        item.type != MediaType.AUDIO &&
            item.originalName.substringBeforeLast('.').let { stem ->
                stem.matches(Regex("(?i)^Instagram(?:_[0-9]+)?$")) ||
                    stem.matches(Regex("(?i)^(?:video|clip|photo|image|reel|post)(?:[_ -]?[0-9]+)?$"))
            }

    /** Stories are never public: they always need a session. */
    private suspend fun resolveStory(link: InstagramLink): List<MediaItem> {
        val session = cookies()?.takeIf { it.isNotBlank() } ?: throw ProviderException(
            ProviderException.Reason.ACCESS_REQUIRED, "Stories доступны только после входа в аккаунт Instagram.", ID,
        )
        return parseApiResponse(apiClient.mediaInfo(link.id, session), link.id)
    }

    private suspend fun <T> attempt(block: suspend () -> T, onLoginWall: () -> Unit): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: ProviderException) {
        if (error.reason == ProviderException.Reason.RATE_LIMITED) throw error
        if (error.reason == ProviderException.Reason.ACCESS_REQUIRED) onLoginWall()
        null
    } catch (_: Exception) {
        null
    }

    companion object {
        const val ID = "instagram"

        /**
         * Builds items from one product node. A carousel is numbered from its first element.
         * Instagram music is a publication-level asset, so it is appended once after the visual media.
         */
        fun itemsFromProduct(product: JSONObject, code: String, fallbackCaption: String? = null): List<MediaItem> {
            val captionName = captionStem(product) ?: fallbackCaption
            val carousel = product.optJSONArray("carousel_media")
            val media = if (carousel != null && carousel.length() > 0) {
                (0 until carousel.length()).mapNotNull(carousel::optJSONObject)
                    .mapIndexedNotNull { index, entry -> mediaItem(entry, "${code}_${index + 1}", code, index + 1, captionName) }
            } else {
                listOfNotNull(mediaItem(product, code, code, null, captionName))
            }
            val music = musicItem(product, code, media.firstOrNull()?.previewUrl)
            return if (music == null) media else media + music
        }

        /** Finds the first usable Instagram music asset regardless of the wrapper used by Posts, Reels or Stories. */
        private fun musicItem(product: JSONObject, code: String, publicationPreview: String?): MediaItem? {
            val asset = findMusicAsset(product, 0) ?: return null
            val url = asset.optString("progressive_download_url").takeIf(::safeMediaUrl) ?: return null
            val rawArtist = asset.optString("display_artist").trim().takeIf { it.isNotBlank() }
            val rawTitle = asset.optString("title").trim().takeIf { it.isNotBlank() }
            val artwork = (
                asset.optString("cover_artwork_uri").takeIf(::safeMediaUrl)
                    ?: asset.optString("cover_artwork_thumbnail_uri").takeIf(::safeMediaUrl)
                )
            val artist = StorageNaming.sanitizeStem(rawArtist)
            val title = StorageNaming.sanitizeStem(rawTitle)
            val stem = StorageNaming.sanitizeStem(
                when {
                    artist != null && title != null -> "$artist - $title"
                    title != null -> title
                    artist != null -> artist
                    else -> StorageNaming.FALLBACK_STEM
                },
            )
            return MediaItem(
                id = "${code}_music",
                providerId = ID,
                type = MediaType.AUDIO,
                originalName = StorageNaming.mediaFileName(stem, null, "mp3"),
                downloadUrl = url,
                previewUrl = publicationPreview,
                audioArtist = rawArtist,
                audioTitle = rawTitle,
                audioArtworkUrl = artwork,
                sourceGroupId = code,
            )
        }

        private fun findMusicAsset(node: Any?, depth: Int): JSONObject? {
            if (depth > 40) return null
            when (node) {
                is JSONObject -> {
                    node.optJSONObject("music_asset_info")?.let { asset ->
                        if (asset.optString("progressive_download_url").takeIf(::safeMediaUrl) != null) return asset
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        findMusicAsset(node.opt(keys.next()), depth + 1)?.let { return it }
                    }
                }
                is JSONArray -> for (index in 0 until node.length()) {
                    findMusicAsset(node.opt(index), depth + 1)?.let { return it }
                }
            }
            return null
        }

        /** Parses /api/v1/media/{id}/info/ (posts, Reels, carousels and Stories share this shape). */
        fun parseApiResponse(body: String, code: String): List<MediaItem> {
            val root = try { JSONObject(body) } catch (_: Exception) {
                throw ProviderException(
                    ProviderException.Reason.ACCESS_REQUIRED, "Сессия Instagram недействительна. Войдите снова.", ID,
                )
            }
            val accountSignal = listOf(root.optString("message"), root.optString("error_type"))
                .joinToString(" ").lowercase()
            if (listOf("challenge_required", "checkpoint_required", "feedback_required",
                    "rate_limit", "please wait a few minutes").any(accountSignal::contains)) {
                throw ProviderException(ProviderException.Reason.RATE_LIMITED,
                    "Instagram ограничил запросы или требует проверку аккаунта.", ID)
            }
            val product = root.optJSONArray("items")?.optJSONObject(0) ?: throw ProviderException(
                ProviderException.Reason.UNSUPPORTED, "Instagram не вернул медиа для этой ссылки.", ID,
            )
            return itemsFromProduct(product, code).ifEmpty {
                throw ProviderException(
                    ProviderException.Reason.UNSUPPORTED, "В ответе Instagram нет доступных файлов.", ID,
                )
            }
        }

        private fun mediaFromMeta(html: String, code: String): MediaItem? {
            val video = meta(html, "og:video:secure_url") ?: meta(html, "og:video")
            val image = meta(html, "og:image")
            val url = (video ?: image)?.takeIf(::safeMediaUrl) ?: return null
            return MediaItem(
                id = code, providerId = ID,
                type = if (video != null) MediaType.VIDEO else MediaType.PHOTO,
                originalName = fileName(url, null, video != null, captionName = captionStemFromMeta(html)),
                downloadUrl = url, previewUrl = image?.takeIf(::safeMediaUrl), sourceGroupId = code,
            )
        }

        /** Fallback for the public embed page when the regular page exposes no media. */
        fun extractEmbedMedia(html: String, code: String): MediaItem? {
            fun urlProperty(key: String): String? {
                val pattern = Regex("\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                val encoded = pattern.find(html)?.groupValues?.get(1) ?: return null
                val decoded = runCatching { JSONObject("{\"url\":\"$encoded\"}").getString("url") }.getOrNull()
                return decoded?.replace("&amp;", "&")?.takeIf(::safeMediaUrl)
            }
            val video = urlProperty("video_url")
            val image = urlProperty("display_url") ?: meta(html, "og:image")?.takeIf(::safeMediaUrl)
            val url = video ?: image ?: return null
            return MediaItem(
                id = code, providerId = ID,
                type = if (video != null) MediaType.VIDEO else MediaType.PHOTO,
                originalName = fileName(url, null, video != null, captionName = captionStemFromMeta(html)),
                downloadUrl = url, previewUrl = image, sourceGroupId = code,
            )
        }

        /** Instagram's shortcode alphabet encodes the numeric media id in base 64. */
        fun mediaId(code: String): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            return code.take(11).fold(BigInteger.ZERO) { id, char ->
                val digit = alphabet.indexOf(char)
                require(digit >= 0) { "Invalid shortcode" }
                id.shiftLeft(6).add(BigInteger.valueOf(digit.toLong()))
            }.toString()
        }

        fun extractEmbeddedMedia(html: String, code: String): List<MediaItem> {
            val expectedId = mediaId(code)
            val scripts = Regex(
                "<script\\b[^>]*\\bdata-sjs\\b[^>]*>(.*?)</script>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            )
            for (script in scripts.findAll(html)) {
                val data = try { JSONObject(script.groupValues[1]) } catch (_: Exception) { continue }
                val product = findMatchingMedia(data, code, expectedId, 0) ?: continue
                val items = itemsFromProduct(product, code, captionStemFromMeta(html))
                if (items.isNotEmpty()) return items
            }
            return emptyList()
        }

        private fun findMatchingMedia(node: Any?, code: String, expectedId: String, depth: Int): JSONObject? {
            if (depth > 40) return null
            when (node) {
                is JSONObject -> {
                    val candidate = node.optJSONObject("if_not_gated_logged_out") ?: node
                    val matches = candidate.optString("code") == code ||
                        candidate.optString("pk") == expectedId || candidate.optString("id") == expectedId
                    if (matches && (candidate.has("carousel_media") || candidate.has("video_versions") ||
                            candidate.has("image_versions2"))
                    ) return candidate
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        findMatchingMedia(node.opt(keys.next()), code, expectedId, depth + 1)?.let { return it }
                    }
                }
                is JSONArray -> for (index in 0 until node.length()) {
                    findMatchingMedia(node.opt(index), code, expectedId, depth + 1)?.let { return it }
                }
            }
            return null
        }

        private fun mediaItem(
            value: JSONObject,
            itemId: String,
            code: String,
            carouselIndex: Int?,
            captionName: String?,
        ): MediaItem? {
            val videos = value.optJSONArray("video_versions")
            val images = value.optJSONObject("image_versions2")?.optJSONArray("candidates")
            val imageEntry = largest(images)
            val videoEntry = bestVideo(videos)
            val image = imageEntry?.optString("url")?.takeIf(::safeMediaUrl)
            val video = videoEntry?.optString("url")?.takeIf(::safeMediaUrl)
            val isVideo = video != null
            val url = video ?: image ?: return null
            return MediaItem(
                id = itemId, providerId = ID,
                type = if (isVideo) MediaType.VIDEO else MediaType.PHOTO,
                originalName = fileName(url, carouselIndex, isVideo,
                    value.optString("original_filename").ifBlank {
                        value.optString("filename").ifBlank {
                            (if (isVideo) videoEntry else imageEntry)?.optString("filename").orEmpty()
                        }
                    }, captionName),
                downloadUrl = url, previewUrl = image,
                audioAvailable = if (isVideo && value.has("has_audio")) value.optBoolean("has_audio") else null,
                sourceGroupId = code, position = carouselIndex,
                // Informational only. Instagram bulk always uses the best available
                // variant even if the API doesn't expose reliable dimensions.
                qualityLabel = if (isVideo) videoEntry?.let { entry ->
                    val width = entry.optInt("width")
                    val height = entry.optInt("height")
                    if (width in 144..10000 && height in 144..10000)
                        "${width}×${height}" else null
                } else null,
            )
        }

        /** Choose the highest-area available progressive video without new requests. */
        private fun bestVideo(values: JSONArray?): JSONObject? = values?.let { array ->
            (0 until array.length()).mapNotNull(array::optJSONObject)
                .filter { safeMediaUrl(it.optString("url")) }
                .maxWithOrNull(compareBy<JSONObject> { entry ->
                    val width = entry.optInt("width").coerceAtLeast(0)
                    val height = entry.optInt("height").coerceAtLeast(0)
                    if (width > 0 && height > 0) width.toLong() * height else 0L
                }.thenBy { it.optInt("width", 0) }
                    .thenBy { it.optLong("bitrate", 0L) })
        }

        private fun largest(values: JSONArray?): JSONObject? = values?.let { array ->
            (0 until array.length()).mapNotNull(array::optJSONObject)
                .filter { safeMediaUrl(it.optString("url")) }
                .maxByOrNull { it.optInt("width", 0) }
        }

        /** Use the publication caption when present, then a readable media filename. */
        private fun fileName(
            url: String,
            carouselIndex: Int?,
            isVideo: Boolean,
            supplied: String? = null,
            captionName: String? = null,
        ): String {
            val pathName = runCatching { URI(url).path.substringAfterLast('/') }.getOrNull()
            fun readableOrNull(candidate: String?): String? =
                StorageNaming.meaningfulStem(candidate)?.takeUnless { stem ->
                    stem.matches(Regex("(?i)^(?:video|clip|photo|image|reel|post)(?:[_ -]?[0-9]+)?$"))
                }
            val stem = captionName ?: readableOrNull(supplied?.substringBeforeLast('.'))
                ?: readableOrNull(pathName?.substringBeforeLast('.'))
            return StorageNaming.mediaFileName(stem, carouselIndex, if (isVideo) "mp4" else "jpg")
        }

        private fun captionStem(product: JSONObject): String? {
            val caption = product.optJSONObject("caption")?.optString("text").orEmpty()
                .ifBlank { product.optString("caption_text") }
                .ifBlank {
                    product.optJSONObject("edge_media_to_caption")?.optJSONArray("edges")
                        ?.optJSONObject(0)?.optJSONObject("node")?.optString("text").orEmpty()
                }
            val author = product.optJSONObject("user")?.optString("username").orEmpty()
                .ifBlank { product.optJSONObject("owner")?.optString("username").orEmpty() }
            return cleanCaption(caption, author)
        }

        /**
         * Instagram currently uses several OG/Twitter meta shapes. Some contain
         * a quoted caption with likes/comments metadata; others store a plain
         * description or an account-prefixed headline.
         */
        private fun captionStemFromMeta(html: String): String? =
            listOf("og:description", "twitter:description", "description",
                "og:title", "twitter:title")
                .firstNotNullOfOrNull { key -> meta(html, key)?.let(::captionFromMetadata) }

        private fun captionFromMetadata(value: String): String? {
            val description = value.trim()
            val instagramPrefix = Regex("(?i)\\bon Instagram\\s*:\\s*").find(description)
            if (instagramPrefix != null) {
                val tail = description.substring(instagramPrefix.range.last + 1)
                return cleanCaption(tail.trim().trim('"', '“', '”'))
            }
            // Typical og:description: "123 likes, 4 comments - user on date: \"caption\"".
            val quoted = Regex("[:：]\\s*[\\\"“](.+?)[\\\"”](?:\\s|$)", RegexOption.DOT_MATCHES_ALL)
                .find(description)?.groupValues?.getOrNull(1)
            if (quoted != null) return cleanCaption(quoted)
            // Never mistakenly name a video after the likes/comments boilerplate.
            if (Regex("(?i)\\b(?:likes|comments|views|instagram photos|followers)\\b")
                    .containsMatchIn(description)) return null
            // A bare description is itself a caption; a username-prefixed title
            // without "on Instagram:" is too ambiguous to trust.
            if (description.equals("Instagram", ignoreCase = true)) return null
            return cleanCaption(description)
        }

        private fun cleanCaption(raw: String?, author: String = ""): String? {
            if (raw.isNullOrBlank()) return null
            val withoutAuthor = if (author.isNotBlank()) {
                raw.replaceFirst(Regex("^\\s*@${Regex.escape(author)}(?=\\s|[.:—–-])\\s*", RegexOption.IGNORE_CASE), "")
            } else raw
            val withoutTags = withoutAuthor
                .replace(Regex("(?<![\\p{L}\\p{N}_])#[\\p{L}\\p{M}\\p{N}_]+"), " ")
                .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\s+([,.;:!?])"), "$1")
            // Clean punctuation left around removed hashtags/links, then shorten
            // at a word boundary when possible. Do not arbitrarily cut a word.
            val cleaned = StorageNaming.sanitizeStem(withoutTags, maxLength = 240)
                ?.trim(' ', '.', ',', ';', ':', '!', '?', '-', '—', '–')
                ?.takeIf { stem -> stem.any(Char::isLetterOrDigit) &&
                    !stem.equals("Instagram", true) } ?: return null
            if (cleaned.length <= 80) return cleaned
            val boundary = cleaned.take(81).lastIndexOf(' ')
            return (if (boundary >= 25) cleaned.take(boundary) else cleaned.take(80))
                .trimEnd(' ', '.', ',', ';', ':', '!', '?', '-', '—', '–')
                .takeIf(String::isNotBlank)
        }

        fun meta(html: String, property: String): String? {
            val tags = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
            val attrs = Regex("([\\w:-]+)\\s*=\\s*([\"'])(.*?)\\2", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            return tags.findAll(html).mapNotNull { tag ->
                val values = attrs.findAll(tag.value).associate { it.groupValues[1].lowercase() to it.groupValues[3] }
                if (values["property"] == property || values["name"] == property) values["content"]?.let(::unescape) else null
            }.firstOrNull()
        }

        private fun unescape(value: String) = value.replace("&amp;", "&").replace("&quot;", "\"")

        fun safeMediaUrl(value: String): Boolean = try {
            val uri = URI(value)
            uri.scheme == "https" && uri.userInfo == null && uri.host?.lowercase()?.let {
                it == "instagram.com" || it.endsWith(".instagram.com") || it == "fbcdn.net" || it.endsWith(".fbcdn.net") ||
                    it == "cdninstagram.com" || it.endsWith(".cdninstagram.com")
            } == true
        } catch (_: Exception) { false }
    }
}

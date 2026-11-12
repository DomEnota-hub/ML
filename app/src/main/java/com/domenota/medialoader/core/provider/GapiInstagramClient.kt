package com.domenota.medialoader.core.provider

import com.domenota.medialoader.core.model.MediaItem
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class GapiFailureKind {
    INVALID_KEY, QUOTA_EXHAUSTED, RATE_LIMITED, NOT_FOUND, UNSUPPORTED, TEMPORARY, UNKNOWN,
}

/** Deliberately excludes server text and transport causes, which can contain credentials. */
class GapiException(
    val kind: GapiFailureKind,
    val retryAfterMillis: Long? = null,
) : Exception("GAPI: ${kind.name}")

data class GapiResponse(val status: Int, val body: String, val retryAfterMillis: Long? = null) {
    override fun toString(): String = "GapiResponse(status=$status, retryAfterMillis=$retryAfterMillis)"
}

/** Public GAPI extraction only. Account sessions and fallback/retry policy belong to the caller. */
class GapiInstagramClient(
    private val transport: suspend (path: String, key: String) -> GapiResponse = ::fetchGapi,
) {
    suspend fun resolve(url: String, key: String): List<MediaItem> {
        currentCoroutineContext().ensureActive()
        val source = url.trim()
        val link = InstagramLinkParser.parse(source) ?: throw GapiException(GapiFailureKind.UNSUPPORTED)
        val uri = try { URI(source) } catch (_: Exception) { throw GapiException(GapiFailureKind.UNSUPPORTED) }
        if (uri.userInfo != null || uri.port !in listOf(-1, if (uri.scheme == "http") 80 else 443)) {
            throw GapiException(GapiFailureKind.UNSUPPORTED)
        }
        if (key.isBlank() || key.length > 4096 || key.any(Character::isISOControl)) {
            throw GapiException(GapiFailureKind.INVALID_KEY)
        }
        val path = if (link.isStory) {
            val username = uri.path.trim('/').split('/').getOrNull(1).orEmpty()
            if (!username.matches(Regex("[A-Za-z0-9_.]{1,30}")) || username in setOf(".", "..")) {
                throw GapiException(GapiFailureKind.UNSUPPORTED)
            }
            "/v2/instagram/stories?username=${encode(username)}"
        } else "/v2/instagram/media?url=${encode(source)}"
        val response = try {
            transport(path, key)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: GapiException) {
            throw error
        } catch (_: IOException) {
            throw GapiException(GapiFailureKind.TEMPORARY)
        } catch (_: Exception) {
            throw GapiException(GapiFailureKind.UNKNOWN)
        }
        currentCoroutineContext().ensureActive()
        if (response.body.length > MAX_GAPI_BODY_BYTES) throw GapiException(GapiFailureKind.TEMPORARY)
        if (response.status !in 200..299) throw GapiException(failureKind(response), response.retryAfterMillis)
        val root = try { JSONObject(response.body) } catch (_: Exception) {
            throw GapiException(GapiFailureKind.UNKNOWN)
        }
        if (root.optString("platform") != "instagram") throw GapiException(GapiFailureKind.UNSUPPORTED)
        val publication = if (link.isStory) requestedStory(root, link.id) else root
        return mediaItems(publication, link.id, source).ifEmpty { throw GapiException(GapiFailureKind.UNSUPPORTED) }
    }

    private fun requestedStory(root: JSONObject, id: String): JSONObject {
        if (storyId(root) == id) return singleStory(root)
        val stories = root.optJSONArray("stories") ?: root.optJSONArray("media")
            ?: throw GapiException(GapiFailureKind.UNSUPPORTED)
        val entries = (0 until stories.length()).mapNotNull(stories::optJSONObject)
        val matching = entries.filter { storyId(it) == id }
        if (matching.size == 1) return singleStory(matching.single())
        // Missing IDs are ambiguous even for a collection containing only one active story.
        throw GapiException(if (matching.isEmpty() && entries.isNotEmpty() && entries.all { storyId(it) != null })
            GapiFailureKind.NOT_FOUND else GapiFailureKind.UNSUPPORTED)
    }

    private fun singleStory(story: JSONObject): JSONObject {
        if (story.has("stories") || story.optString("type") == "album" ||
            (story.optJSONArray("media")?.length() ?: 1) != 1) {
            throw GapiException(GapiFailureKind.UNSUPPORTED)
        }
        return story
    }

    private fun storyId(story: JSONObject): String? = listOf("id", "story_id", "pk")
        .firstNotNullOfOrNull { key -> story.opt(key)?.takeUnless { it == JSONObject.NULL }
            ?.toString()?.takeIf { it.matches(Regex("[0-9]{5,25}")) } }

    private fun mediaItems(root: JSONObject, code: String, source: String): List<MediaItem> {
        val media = root.optJSONArray("media") ?: JSONArray().put(root)
        if (media.length() > MAX_GAPI_MEDIA) throw GapiException(GapiFailureKind.UNSUPPORTED)
        val album = root.optString("type") == "album" || media.length() > 1
        val thumbnail = safeString(root, "thumbnail")
        val quality = mutableMapOf<Int, String?>()
        val children = JSONArray()
        for (index in 0 until media.length()) {
            val entry = media.optJSONObject(index)
            val child = JSONObject()
            val direct = entry?.let { safeString(it, "url") }
            val preview = entry?.let { safeString(it, "thumbnail") } ?: thumbnail
            when (entry?.optString("type")) {
                "video" -> if (direct != null) {
                    child.put("video_versions", JSONArray().put(JSONObject().put("url", direct)))
                    child.put("has_audio", true)
                    if (preview != null) child.put("image_versions2", imageVersions(preview))
                }
                "image" -> if (direct != null) child.put("image_versions2", imageVersions(direct))
            }
            quality[index + 1] = (entry?.opt("quality") as? String)?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= 80 && it.none(Character::isISOControl) }
            children.put(child)
        }
        // Reuse the existing pure mapping and caption policy; this never constructs a provider,
        // reads cookies, requests Instagram metadata, or adds publication-level music assets.
        val product = if (album) JSONObject().put("carousel_media", children)
            else children.optJSONObject(0) ?: return emptyList()
        val title = root.opt("title") as? String
        val author = (root.opt("author") as? String) ?: root.optJSONObject("author")?.optString("username")
        // The usual title keeps absolute priority. Recover ONLY an absent/unusable
        // title from explicit caption fields already present in the same response.
        // No extra HTTP request, cookies, URL replacement or guessed text.
        val caption = if (InstagramProvider.cleanCaption(title, author.orEmpty()) != null) title
            else listOf(root.opt("caption_text") as? String, root.opt("caption") as? String,
                root.optJSONObject("caption")?.opt("text") as? String)
                .firstOrNull { InstagramProvider.cleanCaption(it, author.orEmpty()) != null }
        (caption ?: title)?.let { product.put("caption", JSONObject().put("text", it)) }
        if (!author.isNullOrBlank()) product.put("user", JSONObject().put("username", author))
        return InstagramProvider.itemsFromProduct(product, code).map { item -> item.copy(
            sourcePageUrl = source,
            qualityLabel = quality[item.position ?: 1],
        ) }
    }

    private fun safeString(root: JSONObject, key: String): String? =
        (root.opt(key) as? String)?.takeIf { value ->
            if (!InstagramProvider.safeMediaUrl(value)) return@takeIf false
            val host = URI(value).host.lowercase(Locale.ROOT)
            // Instagram page URLs can trigger saved-cookie routing in the downloader.
            // GAPI assets must stay on CDN hosts so this path never uses an Instagram session.
            host == "fbcdn.net" || host.endsWith(".fbcdn.net") ||
                host == "cdninstagram.com" || host.endsWith(".cdninstagram.com")
        }

    private fun imageVersions(url: String): JSONObject =
        JSONObject().put("candidates", JSONArray().put(JSONObject().put("url", url)))

    private fun failureKind(response: GapiResponse): GapiFailureKind = gapiFailureKind(response)
}

internal fun gapiFailureKind(response: GapiResponse): GapiFailureKind {
    if (response.status == 401) return GapiFailureKind.INVALID_KEY
    if (response.status in setOf(402, 429)) {
        val error = try { JSONObject(response.body) } catch (_: Exception) { null }
        // Balance/usage metadata does not describe why this request failed.
        val descriptions = listOf(error?.opt("detail"), error?.opt("message"))
            .filterIsInstance<String>()
        val exhausted = Regex("exhaust|exceed|deplet|insufficient|out of|no (?:remaining )?tokens|reached|used up|zero|\\b0\\b")
        if (descriptions.any { description ->
            val detail = description.lowercase(Locale.ROOT)
            val monthlyQuota = detail.contains("month") && (detail.contains("quota") || detail.contains("limit") || detail.contains("token"))
            val tokenBalance = detail.contains("token") && (detail.contains("balance") || detail.contains("insufficient") || detail.contains("out of") || detail.contains("no tokens"))
            (monthlyQuota || tokenBalance) && exhausted.containsMatchIn(detail)
        }) return GapiFailureKind.QUOTA_EXHAUSTED
    }
    return when (response.status) {
        429 -> GapiFailureKind.RATE_LIMITED
        400, 422 -> GapiFailureKind.UNSUPPORTED
        404 -> GapiFailureKind.NOT_FOUND
        408, 425 -> GapiFailureKind.TEMPORARY
        in 500..599 -> GapiFailureKind.TEMPORARY
        else -> GapiFailureKind.UNKNOWN
    }
}

private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

internal const val MAX_GAPI_BODY_BYTES = 1_048_576
private const val MAX_GAPI_MEDIA = 1000

/** Only relative API routes are accepted and redirects never receive the API key. */
internal suspend fun fetchGapi(path: String, key: String): GapiResponse = withContext(Dispatchers.IO) {
    if (!path.startsWith("/v2/instagram/media?url=") && !path.startsWith("/v2/instagram/stories?username=") &&
        !path.startsWith("/v2/vk?url=")) {
        throw GapiException(GapiFailureKind.UNSUPPORTED)
    }
    val connection = URI("https://api.gapi.dev$path").toURL().openConnection() as HttpURLConnection
    try {
        currentCoroutineContext().ensureActive()
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("X-API-Key", key)
        connection.setRequestProperty("Accept", "application/json")
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val bytes = stream?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_GAPI_BODY_BYTES) throw GapiException(GapiFailureKind.TEMPORARY)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: ByteArray(0)
        currentCoroutineContext().ensureActive()
        GapiResponse(status, String(bytes, Charsets.UTF_8), retryAfterMillis(connection.getHeaderField("Retry-After")))
    } finally {
        connection.disconnect()
    }
}

private fun retryAfterMillis(value: String?): Long? {
    val header = value?.trim() ?: return null
    header.toLongOrNull()?.takeIf { it >= 0 }?.let { return it.coerceAtMost(Long.MAX_VALUE / 1000) * 1000 }
    return try {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        format.timeZone = TimeZone.getTimeZone("GMT")
        format.isLenient = false
        format.parse(header)?.time?.let { (it - System.currentTimeMillis()).coerceAtLeast(0) }
    } catch (_: Exception) { null }
}

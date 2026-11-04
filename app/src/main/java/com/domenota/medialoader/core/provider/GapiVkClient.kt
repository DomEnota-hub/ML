package com.domenota.medialoader.core.provider

import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.storage.StorageNaming
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** Public video/clip extraction only. Community scans use their existing VK API. */
class GapiVkClient(
    private val transport: suspend (path: String, key: String) -> GapiResponse = ::fetchGapi,
) {
    suspend fun resolve(url: String, key: String): List<MediaItem> {
        currentCoroutineContext().ensureActive()
        val requested = try { URI(url.trim()) } catch (_: Exception) { throw GapiException(GapiFailureKind.UNSUPPORTED) }
        if (requested.port !in listOf(-1, 443)) throw GapiException(GapiFailureKind.UNSUPPORTED)
        val source = VkLinkParser.parse(url)?.canonicalUrl()
            ?: throw GapiException(GapiFailureKind.UNSUPPORTED)
        val uri = URI(source)
        // GAPI explicitly does not support embedded players; preserve the legacy resolver for them.
        if (!uri.path.matches(Regex("/(?:video|clip)-?\\d+_\\d+", RegexOption.IGNORE_CASE))) {
            throw GapiException(GapiFailureKind.UNSUPPORTED)
        }
        if (key.isBlank() || key.length > 4096 || key.any(Character::isISOControl)) {
            throw GapiException(GapiFailureKind.INVALID_KEY)
        }
        val response = try {
            transport("/v2/vk?url=${URLEncoder.encode(source, "UTF-8")}", key)
        } catch (e: CancellationException) { throw e }
        catch (e: GapiException) { throw e }
        catch (_: IOException) { throw GapiException(GapiFailureKind.TEMPORARY) }
        catch (_: Exception) { throw GapiException(GapiFailureKind.UNKNOWN) }
        currentCoroutineContext().ensureActive()
        if (response.body.length > MAX_GAPI_BODY_BYTES) throw GapiException(GapiFailureKind.TEMPORARY)
        if (response.status !in 200..299) throw GapiException(gapiFailureKind(response), response.retryAfterMillis)
        val root = try { JSONObject(response.body) } catch (_: Exception) { throw GapiException(GapiFailureKind.UNKNOWN) }
        if (root.optString("platform") != "vk" || root.optString("type") != "video") {
            throw GapiException(GapiFailureKind.UNSUPPORTED)
        }
        val media = root.optJSONArray("media") ?: throw GapiException(GapiFailureKind.UNSUPPORTED)
        if (media.length() !in 1..100) throw GapiException(GapiFailureKind.UNSUPPORTED)
        val id = uri.path.substringAfterLast('/').replace(Regex("^(?:video|clip)", RegexOption.IGNORE_CASE), "")
        val title = (root.opt("title") as? String)?.take(500)
        val stem = StorageNaming.sanitizeStem(title.orEmpty()) ?: id
        val preview = (root.opt("thumbnail") as? String)?.takeIf(VkDirectMedia::isSafeUrl)
        // All entries are alternative qualities of one video, never carousel positions.
        val qualities = (0 until media.length()).map { index ->
            val entry = media.optJSONObject(index) ?: throw GapiException(GapiFailureKind.UNSUPPORTED)
            val direct = (entry.opt("url") as? String)?.takeIf(VkDirectMedia::isSafeUrl)
                ?: throw GapiException(GapiFailureKind.UNSUPPORTED)
            val height = Regex("([1-9][0-9]{1,4})p").matchEntire(entry.optString("quality"))
                ?.groupValues?.get(1)?.toIntOrNull() ?: throw GapiException(GapiFailureKind.UNSUPPORTED)
            if (entry.optString("type") != "video" || entry.optString("ext") != "mp4") {
                throw GapiException(GapiFailureKind.UNSUPPORTED)
            }
            height to direct
        }.distinctBy { it.first }.sortedByDescending { it.first }
        val defaultHeight = qualities.firstOrNull { it.first <= VkProvider.MAX_DEFAULT_HEIGHT }?.first
            ?: qualities.last().first
        val videos = qualities.map { (height, direct) ->
            MediaItem(
                id = "${id}_${height}p", providerId = VkProvider.ID, type = MediaType.VIDEO,
                originalName = "${stem}_${height}p.mp4", downloadUrl = direct, previewUrl = preview,
                audioAvailable = true, qualityLabel = "${height}p", group = VkProvider.VIDEO_GROUP,
                sourcePageUrl = source, preselected = height == defaultHeight,
            )
        }
        // The smallest muxed MP4 is enough for MP3 extraction and avoids fetching 1080p for audio.
        val audio = MediaItem(
            id = "${id}_audio", providerId = VkProvider.ID, type = MediaType.AUDIO,
            originalName = "$stem.mp3", downloadUrl = qualities.last().second, previewUrl = preview,
            qualityLabel = "MP3 · 192 kbps", sourcePageUrl = source,
        )
        return videos + audio
    }
}

/** Direct signed media URLs are never handed to the VK page extractor or account cookie jar. */
object VkDirectMedia {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    const val REFERER = "https://vk.com/"

    fun isSafeUrl(value: String): Boolean = try {
        val uri = URI(value)
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            listOf("vkuser.net", "userapi.com", "vkuserlive.net", "vk-cdn.net", "vkcdn.ru").any {
                host == it || host.endsWith(".$it")
            }
    } catch (_: Exception) { false }
}
